'use strict';
const fs = require('node:fs');
const path = require('node:path');

function credentials() {
  if (process.env.VISIONGUARD_TEST_USERNAME && process.env.VISIONGUARD_TEST_PASSWORD) return { username: process.env.VISIONGUARD_TEST_USERNAME, password: process.env.VISIONGUARD_TEST_PASSWORD };
  const file = process.env.VISIONGUARD_TEST_ACCOUNTS_PATH || path.resolve(__dirname, '../.local/e2e-server', process.env.VISIONGUARD_CHANNEL || 'account-stream-e2e', 'test-accounts.json');
  if (!fs.existsSync(file)) throw new Error('Set VISIONGUARD_TEST_ACCOUNTS_PATH to the isolated private account file, or set VISIONGUARD_TEST_USERNAME/PASSWORD.');
  const values = JSON.parse(fs.readFileSync(file, 'utf8'));
  const account = values.find(value => value.username === (process.env.VISIONGUARD_TEST_USERNAME || 'vg-test'));
  if (!account || !account.password) throw new Error('The requested isolated test account is absent.');
  return account;
}
async function login(service, component, name) {
  const account = credentials();
  const response = await fetch(service.replace(/^ws/, 'http').replace(/\/$/, '') + '/api/account/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...account, component, deviceIdentity: require('node:crypto').createHash('sha256').update('isolated-probe|' + name).digest('hex'), deviceModel: '测试设备' }), signal: AbortSignal.timeout(15000),
  });
  if (!response.ok) throw new Error(`Isolated account login failed (${response.status}).`);
  const session = await response.json();
  if (!session.ok || !session.token || !session.device?.deviceId) throw new Error('Incomplete account login response.');
  return session;
}
async function logout(service, token) {
  if (!token) return;
  await fetch(service.replace(/^ws/, 'http').replace(/\/$/, '') + '/api/account/logout', {
    method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: '{}', signal: AbortSignal.timeout(5000),
  }).catch(() => {});
}
module.exports = { credentials, login, logout };
