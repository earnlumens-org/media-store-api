#!/usr/bin/env node
// Local XDR-signing sidecar for loadtest/checkout.js (staging/testnet ONLY).
//
// k6 cannot sign Stellar transactions, so checkout.js delegates signing to
// this tiny HTTP helper. It signs with ONE test wallet whose secret comes
// from the environment — never hardcode or commit a secret, and never point
// this at a mainnet wallet.
//
//   npm i @stellar/stellar-sdk            # one-off, anywhere
//   SIGNER_SECRET=S... node tools/xdr-signer.mjs   # listens on :8787
//
// POST / {unsignedXdr, networkPassphrase} → {signedXdr}

import http from 'node:http';
import { Keypair, TransactionBuilder } from '@stellar/stellar-sdk';

const SECRET = process.env.SIGNER_SECRET;
const PORT = parseInt(process.env.PORT || '8787', 10);

if (!SECRET) {
  console.error('SIGNER_SECRET env var is required (S... secret of a TEST wallet)');
  process.exit(1);
}
const keypair = Keypair.fromSecret(SECRET);
console.log(`xdr-signer: signing as ${keypair.publicKey()} on 127.0.0.1:${PORT}`);

http.createServer((req, res) => {
  if (req.method !== 'POST') { res.writeHead(405).end(); return; }
  let body = '';
  req.on('data', (c) => { body += c; });
  req.on('end', () => {
    try {
      const { unsignedXdr, networkPassphrase } = JSON.parse(body);
      const tx = TransactionBuilder.fromXDR(unsignedXdr, networkPassphrase);
      tx.sign(keypair);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ signedXdr: tx.toEnvelope().toXDR('base64') }));
    } catch (e) {
      res.writeHead(400, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: e.message }));
    }
  });
}).listen(PORT, '127.0.0.1');
