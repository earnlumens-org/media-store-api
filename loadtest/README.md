# EarnLumens — k6 load test suite

Scripts de la **tarea 3.4** de `SCALABILITY-AUDIT.md` (documento de auditoría
en la raíz del workspace, fuera de este repo) — Fase 3, escala horizontal real.
Su salida decide los números definitivos de
`MONGO_MAX_POOL_SIZE`, `server.tomcat.threads.max`, `max-instances` de Cloud Run
y el tier de Atlas — con datos, no estimaciones.

> Estos scripts se lanzan **manualmente**. No forman parte del build ni del CI.

## Reglas de juego (antes de lanzar nada)

1. **Apuntar a staging**, o a producción **solo en horario valle y con un
   presupuesto de requests acordado**. Nunca contra Atlas sin avisar.
2. **`checkout.js` jamás contra producción con un wallet real**: cada iteración
   crea órdenes reales y, con firmante, pagos on-chain reales. Staging +
   Stellar **testnet** únicamente.
3. Avisar antes de lanzar: el rate limiting de borde (cdn-worker / tenants-router)
   y los tiers del `RateLimitFilter` están activos y van a morder si no se
   coordina IP/presupuesto.
4. Monitorear durante el test: Atlas (Query Profiler, opcounters, conexiones),
   Cloud Run (instancias, CPU, latencia), CF Analytics (hit rate). Las
   condiciones R1–R6 de adopción de Redis se evalúan con estos datos.

## Requisitos

- [k6](https://k6.io/docs/get-started/installation/) ≥ 0.50
- Para el checkout completo: Node 20+ y `@stellar/stellar-sdk` (sidecar de firma)

## Variables comunes

| Variable | Default | Descripción |
|---|---|---|
| `BASE_URL` | `https://app-dev.earnlumens.org` | Origen del tenant bajo prueba |
| `AUTH_TOKEN` | — | JWT de acceso (Bearer) para escenarios autenticados |
| `RPS` | `10` | Tasa de llegada (requests/s) del escenario |
| `DURATION` | `2m` | Duración del escenario |
| `VUS` | `20` | VUs pre-asignados (el executor escala hasta ×4) |

Obtener `AUTH_TOKEN`: iniciar sesión en el entorno objetivo y copiar el Bearer
que envía la SPA (DevTools → Network). El token expira rápido (2 min de access
token): para tests largos usar un token de un entorno con expiry extendido o
renovarlo entre escenarios.

## Escenarios

### 1. Feed anónimo — `feed-anonymous.js`

Valida la Fase 1: las lecturas públicas anónimas deben servirse mayormente del
edge. Mide `edge_cache_hit` con el header `x-edge-cache` del tenants-router
(umbral: > 70 %) y p99 < 500 ms.

```bash
k6 run feed-anonymous.js -e BASE_URL=https://earnlumens.org -e RPS=50 -e DURATION=5m
```

### 2. Búsqueda `$text` — `search.js`

Valida la Fase 2 (2.1): búsqueda sobre text indexes, sin collection scans.
Es el escenario que más castiga a Atlas (respuestas no cacheables). Con tráfico
anónimo el presupuesto distribuido 25/h/IP (tarea 3.1) responde `loginRequired`
— para medir la base de datos usar `AUTH_TOKEN`. Ajustar `QUERIES` en el script
a términos que existan en el dataset objetivo.

```bash
k6 run search.js -e BASE_URL=https://earnlumens.org -e AUTH_TOKEN=$TOKEN -e RPS=10
```

Durante el run, verificar en el Atlas Profiler que las queries usan
`idx_text_search` (IXSCAN/TEXT) y vigilar el namespace `rate_limit_counters`
(triggers R1/R2).

### 3. Feed autenticado — `feed-authenticated.js`

Valida la Fase 2 (2.3): filtro de idiomas desde claims del JWT (cero lookups a
`users`) y bypass total del edge (`private, no-store`). Mide la capacidad real
de origen para usuarios logueados; `edge_bypass` debe ser ~100 %.

```bash
k6 run feed-authenticated.js -e BASE_URL=https://earnlumens.org -e AUTH_TOKEN=$TOKEN -e RPS=20
```

### 4. Checkout prepare → submit 202 → polling — `checkout.js`

Valida la Fase 2 (2.2): el submit responde 202 + `PROCESSING` sin bloquear el
thread en la confirmación on-chain. Criterio del audit: **p99 del submit < 2 s**.

k6 no firma transacciones Stellar; el pipeline completo necesita el sidecar de
firma local con un wallet de **testnet**:

```bash
cd tools && npm i @stellar/stellar-sdk && cd ..
SIGNER_SECRET=S... node tools/xdr-signer.mjs &   # firma en 127.0.0.1:8787

k6 run checkout.js \
  -e BASE_URL=https://staging.earnlumens.org -e AUTH_TOKEN=$TOKEN \
  -e ENTRY_ID=<entry de pago> -e BUYER_WALLET=G... \
  -e SIGNER_URL=http://127.0.0.1:8787 -e RPS=1 -e DURATION=5m
```

Sin `SIGNER_URL` el escenario degrada a **prepare + polling** (sin submit):
sigue siendo útil para dimensionar threads/pools porque prepare es el paso que
toca Horizon y Mongo en el request thread.

### 5. HLS vía cdn-worker — `hls.js`

Valida la Fase 1 (1.3) y la ruta de entrega: playlist (TTL 1 h) + segmentos
(7 d, inmutables) desde el edge, entitlement cacheado 30 min. Umbral:
> 90 % de hits de edge en segmentos calientes.

```bash
k6 run hls.js -e BASE_URL=https://earnlumens.org -e AUTH_TOKEN=$TOKEN \
  -e ENTRY_ID=<entry con HLS> -e RPS=30
```

## Salidas concretas del test (tarea 3.4)

Con los resultados, fijar:

1. **`MONGO_MAX_POOL_SIZE`** por instancia — regla: `límite_del_tier × 0.8 / max_instances`
   (env ya soportada por `MongoPoolConfig`, default 40).
2. **`server.tomcat.threads.max`** acorde al vCPU por instancia y al punto donde
   la latencia se degrada (overridable por properties desde la Fase 1).
3. **`max-instances` de Cloud Run** — fijar vía `gcloud run services update ... --max-instances=N`.
4. **Tier de Atlas** (M10/M30) — solo subir si los datos lo demuestran.
5. **Validación de 3.1 bajo carga**: los límites (auth 10/min/IP, búsqueda
   anónima 25/h/IP) deben ser independientes del número de instancias; revisar
   triggers R1/R2/R6 sobre `rate_limit_counters`.

**Criterio de salida de la fase:** p99 < 500 ms en lecturas públicas al objetivo
de concurrencia; capacidad ~lineal al duplicar instancias; límites de rate
efectivos e independientes del número de instancias.
