import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import express from 'express';
import test from 'node:test';
import { registration } from './helpers/accounts';

const directory = fs.mkdtempSync(path.join(os.tmpdir(),'vg-cache-'));
process.env.VISIONGUARD_DATA_DIR=directory;
test.after(()=>fs.rmSync(directory,{recursive:true,force:true}));
const {screenshotCache,maintainLegacyCache,parseCacheReport}=require('../src/services/CacheMaintenance') as typeof import('../src/services/CacheMaintenance');

test('only expired unreferenced images removed; hardlink unlink does not claim freed blocks',()=>{
  const root=path.join(directory,'screenshots');fs.mkdirSync(root,{recursive:true});
  const old=Date.now()-80*3600000;
  const files=['old-file-123.png','referenced-123.png','fresh-file-123.png','linked-file-123.png'];
  for(const name of files){const file=path.join(root,name);fs.writeFileSync(file,'image');if(!name.startsWith('fresh'))fs.utimesSync(file,old/1000,old/1000);}
  fs.linkSync(path.join(root,files[3]),path.join(directory,'kept-hardlink.png'));
  fs.writeFileSync(path.join(root,'config.json'),'private');
  const protectedFile=path.resolve(root,files[1]);
  assert.equal(screenshotCache(root,new Set([protectedFile]),false).categories[0].cleanableFiles,2);
  const report=screenshotCache(root,new Set([protectedFile]),true);
  assert.equal(report.removedFiles,2);assert.equal(report.removedBytes,10);
  assert.ok(fs.existsSync(protectedFile));assert.ok(fs.existsSync(path.join(root,files[2])));assert.ok(fs.existsSync(path.join(root,'config.json')));
  assert.equal(screenshotCache(root,new Set([protectedFile]),true).removedFiles,0);
});
test('unreadable legacy metadata protects every screenshot and report boundaries reject malformed data',()=>{
  const root=path.join(directory,'screenshots'),file=path.join(root,'unreadable-123.jpg');fs.writeFileSync(file,'keep');const old=Date.now()-80*3600000;fs.utimesSync(file,old/1000,old/1000);
  fs.writeFileSync(path.join(directory,'alerts.json'),'broken');assert.equal(maintainLegacyCache(true).removedFiles,0);assert.ok(fs.existsSync(file));
  const report=screenshotCache(root,null,false);assert.ok(parseCacheReport(report));
  assert.equal(parseCacheReport({...report,removedFiles:-1}),undefined);
  assert.equal(parseCacheReport({...report,categories:[{...report.categories[0],label:'x'.repeat(65)}]}),undefined);
  assert.equal(parseCacheReport({...report,categories:[{...report.categories[0],cleanableFiles:999}]}),undefined);
});
test('linked directories cannot make cleanup traverse outside its fixed scope',t=>{
  const outside=path.join(directory,'protected-outside'),link=path.join(directory,'linked-cache');fs.mkdirSync(outside);
  const image=path.join(outside,'protected-image-123.png');fs.writeFileSync(image,'keep');const old=Date.now()-80*3600000;fs.utimesSync(image,old/1000,old/1000);
  try { fs.symlinkSync(outside,link,'junction'); } catch { t.skip('Host link creation unavailable');return; }
  const report=screenshotCache(link,new Set(),true);assert.equal(report.removedFiles,0);assert.equal(report.failedFiles,1);assert.ok(fs.existsSync(image));
});
test('cache API isolates accounts, requires console and limits legacy cleanup to administrator',async t=>{
  const {accountStore,accountDirectory}=require('../src/services/AccountStore') as typeof import('../src/services/AccountStore');
  const first=accountStore.createAccount('cache-first','cache-private-password'),second=accountStore.createAccount('cache-second','cache-private-password');
  const login=async(username:string,component:string)=>accountStore.login({...registration(),username,password:'cache-private-password',component});
  const [consoleSession,nodeSession,other]=await Promise.all([login(first.username,'web-console'),login(first.username,'android-notifier'),login(second.username,'web-console')]);
  for(const id of [first.accountId,second.accountId]){const root=path.join(accountDirectory(id),'screenshots');fs.mkdirSync(root,{recursive:true});const file=path.join(root,'expired-file-123.png');fs.writeFileSync(file,'keep');const old=Date.now()-80*3600000;fs.utimesSync(file,old/1000,old/1000);}
  const app=express();app.use(express.json());app.use(require('../src/routes/cache').default);const server=http.createServer(app);await new Promise<void>(r=>server.listen(0,'127.0.0.1',r));t.after(()=>new Promise<void>(r=>server.close(()=>r())));
  const url=`http://127.0.0.1:${(server.address() as any).port}/api/cache`;
  const request=(token:string,body:unknown)=>fetch(url,{method:'POST',headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},body:JSON.stringify(body)});
  assert.equal((await request(nodeSession.token,{scope:'account'})).status,403);
  assert.equal((await request(other.token,{scope:'legacy'})).status,403);
  assert.equal((await request(consoleSession.token,{scope:'account',path:'../'})).status,400);
  const response=await request(consoleSession.token,{scope:'account'});assert.equal(response.status,200);assert.equal((await response.json() as any).removedFiles,1);
  assert.ok(fs.existsSync(path.join(accountDirectory(second.accountId),'screenshots','expired-file-123.png')));
});
