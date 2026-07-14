package org.earnlumens.mediastore.infrastructure.stellar;

import org.earnlumens.mediastore.infrastructure.config.StellarConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.stellar.sdk.Server;
import org.stellar.sdk.exception.BadResponseException;
import org.stellar.sdk.exception.ConnectionErrorException;
import org.stellar.sdk.exception.RequestTimeoutException;
import org.stellar.sdk.exception.TooManyRequestsException;
import org.stellar.sdk.exception.UnknownResponseException;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Rotating pool of Horizon nodes with passive health tracking.
 *
 * <p><b>Why:</b> the public SDF Horizon ({@code horizon.stellar.org}) is
 * rate-limited per IP and explicitly not intended for production volume
 * (SCALABILITY-AUDIT.md §9.3 — the single external dependency on the consumer
 * path that does not auto-scale). This pool spreads idempotent reads across
 * every configured provider and fails over transparently on transport-level
 * errors, multiplying the effective rate-limit budget by the number of
 * healthy nodes at zero cost.
 *
 * <p><b>Configuration:</b> {@code STELLAR_HORIZON_URLS} (comma-separated,
 * priority order). Falls back to the single {@code STELLAR_HORIZON_URL}.
 * When self-hosted Horizon nodes are deployed later, list them first: submits
 * always go to the first healthy node in configured order, and reads rotate
 * across all of them — no code change required.
 *
 * <p><b>Semantics per call type:</b>
 * <ul>
 *   <li><b>Reads</b> ({@link #executeRead}) — round-robin across healthy
 *       nodes; on a <em>node failure</em> (429 / 5xx / timeout / connection
 *       error) the node enters a {@value #COOLDOWN_MS} ms cooldown and the
 *       next node is tried. <em>Definitive answers</em> (4xx such as
 *       account-not-found) propagate immediately — a different node would
 *       give the same answer.</li>
 *   <li><b>Submits</b> ({@link #submitServer}) — exactly ONE node, ONE
 *       attempt, never retried here. Re-submitting a tx whose first attempt
 *       may have landed can surface {@code tx_bad_seq} and misclassify a
 *       successful payment as REJECTED; the existing UNKNOWN → on-chain
 *       verification flow is the only safe retry path
 *       ({@code PaymentService.finalizeSubmission}).</li>
 * </ul>
 *
 * <p>If every node is cooling down, the pool degrades to trying them anyway
 * (never self-inflicts a total blackout).
 */
@Component
public class HorizonServerPool {

    private static final Logger logger = LoggerFactory.getLogger(HorizonServerPool.class);

    /** How long a node is skipped after a transport-level failure. */
    static final long COOLDOWN_MS = 30_000L;

    private static final class Node {
        final String url;
        final Server server;
        volatile long downUntilMs;

        Node(String url, Server server) {
            this.url = url;
            this.server = server;
        }

        boolean isDown(long now) {
            return downUntilMs > now;
        }
    }

    private final List<Node> nodes;
    private final AtomicInteger cursor = new AtomicInteger();

    @Autowired
    public HorizonServerPool(StellarConfig config) {
        this(config.getHorizonUrlList(), Server::new);
    }

    /** Visible for testing — inject mock {@link Server} instances per URL. */
    public HorizonServerPool(List<String> urls, Function<String, Server> serverFactory) {
        if (urls == null || urls.isEmpty()) {
            throw new IllegalArgumentException("At least one Horizon URL is required");
        }
        this.nodes = urls.stream().map(u -> new Node(u, serverFactory.apply(u))).toList();
        logger.info("Horizon pool initialised with {} node(s): {}", nodes.size(), urls);
    }

    public int size() {
        return nodes.size();
    }

    /**
     * True when the exception means "this node misbehaved" (worth failing over)
     * rather than "the answer is no" (a different node would answer the same).
     */
    static boolean isNodeFailure(RuntimeException e) {
        return e instanceof ConnectionErrorException
                || e instanceof RequestTimeoutException
                || e instanceof TooManyRequestsException
                || e instanceof BadResponseException
                || e instanceof UnknownResponseException;
    }

    /**
     * Executes an idempotent Horizon read with rotation and failover.
     * Definitive answers (e.g. {@code AccountNotFoundException},
     * {@code BadRequestException}) propagate untouched; node failures mark the
     * node down and move on to the next one.
     */
    public <T> T executeRead(String opName, Function<Server, T> call) {
        long now = System.currentTimeMillis();
        int start = Math.floorMod(cursor.getAndIncrement(), nodes.size());
        RuntimeException last = null;
        boolean[] attempted = new boolean[nodes.size()];
        // Pass 1: healthy nodes only. Pass 2: cooling-down nodes as a last
        // resort (never blackout when every node is in cooldown).
        for (boolean healthyOnly : new boolean[]{true, false}) {
            for (int i = 0; i < nodes.size(); i++) {
                int idx = (start + i) % nodes.size();
                Node node = nodes.get(idx);
                if (attempted[idx] || (healthyOnly && node.isDown(now))) {
                    continue;
                }
                attempted[idx] = true;
                try {
                    return call.apply(node.server);
                } catch (RuntimeException e) {
                    if (!isNodeFailure(e)) {
                        throw e;
                    }
                    markDown(node, opName, e);
                    last = e;
                }
            }
        }
        throw last != null ? last : new IllegalStateException("No Horizon node available");
    }

    /**
     * Node that receives transaction submissions: the FIRST healthy node in
     * configured order (deterministic; future self-hosted nodes listed first
     * take over automatically). The caller performs exactly one attempt —
     * see class javadoc for why submits are never retried across nodes.
     */
    public Server submitServer() {
        long now = System.currentTimeMillis();
        for (Node n : nodes) {
            if (!n.isDown(now)) {
                return n.server;
            }
        }
        return nodes.get(0).server;
    }

    /**
     * Reports a transport-level submit failure (UNKNOWN outcome) so subsequent
     * submits rotate away from the misbehaving node during its cooldown.
     */
    public void reportSubmitFailure(Server server, Exception e) {
        for (Node n : nodes) {
            if (n.server == server) {
                markDown(n, "submitTransaction", e);
                return;
            }
        }
    }

    /**
     * Base URL for raw-HTTP consumers (e.g. the SDEX orderbook price source),
     * sharing the same rotation and health state as SDK reads.
     */
    public String nextReadUrl() {
        long now = System.currentTimeMillis();
        int start = Math.floorMod(cursor.getAndIncrement(), nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get((start + i) % nodes.size());
            if (!node.isDown(now)) {
                return node.url;
            }
        }
        return nodes.get(start).url;
    }

    /** Marks a node down by URL (for raw-HTTP consumers reporting failures). */
    public void markUrlDown(String url, String reason) {
        for (Node n : nodes) {
            if (n.url.equals(url)) {
                n.downUntilMs = System.currentTimeMillis() + COOLDOWN_MS;
                logger.warn("Horizon node {} marked down for {} ms ({})", url, COOLDOWN_MS, reason);
                return;
            }
        }
    }

    private void markDown(Node node, String opName, Exception e) {
        node.downUntilMs = System.currentTimeMillis() + COOLDOWN_MS;
        logger.warn("Horizon node {} marked down for {} ms after {} failure: {}",
                node.url, COOLDOWN_MS, opName, e.toString());
    }
}
