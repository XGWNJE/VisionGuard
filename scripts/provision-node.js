const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const [deviceId, role, nodeType, platform] = process.argv.slice(2);
const types = { detector: ['visual', 'sensor'], console: ['console'], notifier: ['notification'], lifecycle: ['resident'] };
if (!/^[A-Za-z0-9._-]{1,128}$/.test(deviceId || '') || !Object.prototype.hasOwnProperty.call(types, role) || !types[role].includes(nodeType) || !/^[a-z][a-z0-9-]{0,31}$/.test(platform || '')) {
  console.error('usage: node scripts/provision-node.js <deviceId> <detector|console|notifier|lifecycle> <nodeType> <platform>');
  process.exit(2);
}
const root = path.resolve(__dirname, '..', '.local');
const registryPath = path.join(root, 'node-identities.json');
const registry = fs.existsSync(registryPath) ? JSON.parse(fs.readFileSync(registryPath, 'utf8')) : [];
if (!Array.isArray(registry) || registry.some(item => item.role === role && item.deviceId === deviceId)) {
  console.error('Identity already registered or registry invalid; edit the private registry explicitly to revoke or replace it.');
  process.exit(1);
}
const identity = { deviceId, role, nodeType, platform, apiKey: crypto.randomBytes(32).toString('hex') };
const nodePath = path.join(root, 'nodes', `${role}-${deviceId}.json`);
fs.mkdirSync(path.dirname(nodePath), { recursive: true });
fs.writeFileSync(nodePath, JSON.stringify(identity, null, 2), { mode: 0o600, flag: 'wx' });
fs.writeFileSync(registryPath, JSON.stringify([...registry, identity], null, 2), { mode: 0o600 });
console.log(JSON.stringify({ deviceId, role, nodeType, platform, registryPath, nodePath }));
