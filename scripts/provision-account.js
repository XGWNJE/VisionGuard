'use strict';
// Create accounts while the isolated service is stopped. No passwords in arguments or output.
const path = require('node:path');
function usage() {
  throw new Error('Usage: set VISIONGUARD_DATA_DIR and VISIONGUARD_ACCOUNT_PASSWORD, then node scripts/provision-account.js <username>. Stop the service before provisioning.');
}
async function readPassword() {
  if (process.env.VISIONGUARD_ACCOUNT_PASSWORD) return process.env.VISIONGUARD_ACCOUNT_PASSWORD;
  if (process.stdin.isTTY) return '';
  const chunks = [];
  let bytes = 0;
  for await (const chunk of process.stdin) {
    bytes += chunk.length;
    if (bytes > 2048) throw new Error('Invalid password input');
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString('utf8').replace(/[\r\n]+$/, '');
}
async function main() {
  const username = process.argv[2];
  if (!username || process.argv.length !== 3 || !process.env.VISIONGUARD_DATA_DIR) usage();
  const password = await readPassword();
  if (!password) usage();
  const { accountStore } = require(path.resolve(__dirname, '../server/dist/services/AccountStore.js'));
  const account = accountStore.createAccount(username, password);
  console.log(JSON.stringify({ ok: true, account }));
}
main().catch(error => {
  if (error.message.startsWith('Usage:')) console.error(error.message);
  else console.error(error && error.status ? error.message : 'Account provisioning failed; build the service and check its private data directory.');
  process.exitCode = 1;
});
