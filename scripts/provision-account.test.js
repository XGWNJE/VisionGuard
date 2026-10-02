'use strict';
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const test = require('node:test');

const script = path.join(__dirname, 'provision-account.js');
async function provision(directory, chunks, args = ['pipe-owner'], environment = {}) {
  const child = spawn(process.execPath, [script, ...args], {
    env: { ...process.env, VISIONGUARD_DATA_DIR: directory, VISIONGUARD_ACCOUNT_PASSWORD: '', ...environment },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  let stdout = '', stderr = '';
  child.stdout.on('data', chunk => { stdout += chunk; });
  child.stderr.on('data', chunk => { stderr += chunk; });
  // Ignore an expected pipe closure when invalid arguments are rejected before input.
  child.stdin.on('error', () => {});
  const exited = new Promise((resolve, reject) => { child.once('error', reject); child.once('close', code => resolve({ code, stdout, stderr })); });
  for (const chunk of chunks) {
    child.stdin.write(chunk);
    await new Promise(resolve => setTimeout(resolve, 20));
  }
  child.stdin.end();
  return exited;
}

test('account CLI reads a delayed UTF-8 pipe through EOF and never prints its password', async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'visionguard-account-cli-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const password = 'temporary-管道-password';
  const bytes = Buffer.from(password + '\r\n');
  const result = await provision(directory, [bytes.subarray(0, 11), bytes.subarray(11, 13), bytes.subarray(13)]);
  assert.equal(result.code, 0, result.stderr);
  assert.equal(JSON.parse(result.stdout).account.username, 'pipe-owner');
  assert.equal((result.stdout + result.stderr).includes(password), false);
  const content = fs.readFileSync(path.join(directory, 'accounts.json'), 'utf8');
  assert.equal(content.includes(password), false);
  const stored = JSON.parse(content).accounts[0];
  assert.equal(stored.passwordHash, crypto.scryptSync(password, stored.salt, 64).toString('hex'));
  const duplicate = await provision(directory, [Buffer.from('replacement-password')]);
  assert.equal(duplicate.code, 1);
  assert.equal(duplicate.stderr.includes('replacement-password'), false);
  assert.equal(fs.readFileSync(path.join(directory, 'accounts.json'), 'utf8'), content);
});

test('account CLI rejects empty or excessive input and password arguments without storing or echoing them', async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'visionguard-account-cli-invalid-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  for (const [chunks, args] of [[[], ['pipe-owner']], [[Buffer.alloc(2049, 97)], ['pipe-owner']], [[], ['pipe-owner', 'secret-must-not-be-an-argument']]]) {
    const result = await provision(directory, chunks, args);
    assert.equal(result.code, 1);
    assert.equal((result.stdout + result.stderr).includes('secret-must-not-be-an-argument'), false);
  }
  assert.equal(fs.existsSync(path.join(directory, 'accounts.json')), false);
});
