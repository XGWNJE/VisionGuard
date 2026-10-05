import { useEffect, useRef, useState, type ReactNode } from 'react';
import { accountRequest, type Login } from './account';

type ManagedAccount = { accountId: string; username: string; isAdmin: boolean; enabled: boolean };
export function AccountManagement({login}:{login:Login}) {
  const [accounts,setAccounts] = useState<ManagedAccount[]>([]);
  const [username,setUsername] = useState(''), [password,setPassword] = useState('');
  const [administrator,setAdministrator] = useState(false);
  const [editing,setEditing] = useState<ManagedAccount|null>(null), [resetPassword,setResetPassword] = useState('');
  const [busy,setBusy] = useState(false), [error,setError] = useState(''), [message,setMessage] = useState('');
  const [loading,setLoading] = useState(true);
  const [creating,setCreating] = useState(false);
  async function refresh() { setLoading(true);try {const result=await accountRequest<{accounts:ManagedAccount[]}>('/api/admin/accounts',login.token);setAccounts(result.accounts);}finally{setLoading(false);} }
  useEffect(()=>{let active=true;setLoading(true);void accountRequest<{accounts:ManagedAccount[]}>('/api/admin/accounts',login.token).then(result=>{if(active)setAccounts(result.accounts);}).catch(e=>{if(active)setError(e.message);}).finally(()=>{if(active)setLoading(false);});return()=>{active=false;};},[login.token]);
  async function create() {
    setBusy(true);setError('');setMessage('');
    try {await accountRequest('/api/admin/accounts',login.token,{username:username.trim(),password,isAdmin:administrator});setUsername('');setPassword('');setAdministrator(false);await refresh();setCreating(false);setMessage('账号已创建');}
    catch(e){setError((e as Error).message);}finally{setBusy(false);}
  }
  async function save() {
    if(!editing)return;
    setBusy(true);setError('');setMessage('');
    try {await accountRequest(`/api/admin/accounts/${encodeURIComponent(editing.accountId)}`,login.token,{enabled:editing.enabled,isAdmin:editing.isAdmin,...(resetPassword?{password:resetPassword}:{})},'PATCH');setResetPassword('');setEditing(null);await refresh();setMessage('账号已更新');}
    catch(e){setError((e as Error).message);}finally{setBusy(false);}
  }
  return <div className="scope-list">
    {error&&<section className="panel"><p className="error" role="alert">{error}</p></section>}
    {message&&<section className="panel"><p role="status">{message}</p></section>}
    {creating&&<AccountFormDialog title="创建账号" onClose={()=>{if(!busy)setCreating(false);}}><form onSubmit={e=>{e.preventDefault();void create();}}>
      <label>账号<input required minLength={3} maxLength={64} pattern="[A-Za-z0-9][A-Za-z0-9._\-]{2,63}" autoComplete="off" value={username} onChange={e=>setUsername(e.target.value)} disabled={busy}/></label>
      <label>初始密码<input required type="password" minLength={8} maxLength={256} autoComplete="new-password" value={password} onChange={e=>setPassword(e.target.value)} disabled={busy}/></label>
      <label className="check"><input type="checkbox" checked={administrator} onChange={e=>setAdministrator(e.target.checked)} disabled={busy}/>管理员权限</label>
      {error&&<p className="error" role="alert">{error}</p>}
      <div className="dialog-actions"><button type="button" className="button secondary" disabled={busy} onClick={()=>setCreating(false)}>取消</button><button className="button" disabled={busy}>{busy?'提交中…':'创建账号'}</button></div>
    </form></AccountFormDialog>}
    <section className="panel settings-panel"><div className="account-list-header"><h2>账号列表</h2><button className="button" onClick={()=>{setError('');setMessage('');setCreating(true);}}>创建账号</button></div><p className="subtle">禁用、重置密码或变更权限会撤销该账号的现有会话。至少保留一个启用的管理员。</p>
      {loading&&<p role="status">正在读取账号…</p>}
      {!loading&&!accounts.length&&<button className="button secondary" disabled={busy} onClick={()=>{setError('');void refresh().catch(e=>setError(e.message));}}>重新读取</button>}
      {accounts.map(account=><div className="receipt-row" key={account.accountId}><span><strong>{account.username}{account.accountId===login.account.accountId?'（当前账号）':''}</strong><small>{account.isAdmin?'管理员':'普通账号'} · {account.enabled?'已启用':'已禁用'}</small></span><button className="button secondary" disabled={busy} onClick={()=>{setEditing({...account});setResetPassword('');setError('');setMessage('');}}>管理</button></div>)}
    </section>
    {editing&&<AccountFormDialog title={`管理 ${editing.username}`} onClose={()=>{if(!busy){setEditing(null);setResetPassword('');}}}><form onSubmit={e=>{e.preventDefault();void save();}}>
      <label className="check"><input type="checkbox" checked={editing.enabled} onChange={e=>setEditing({...editing,enabled:e.target.checked})} disabled={busy}/>启用账号</label>
      <label className="check"><input type="checkbox" checked={editing.isAdmin} onChange={e=>setEditing({...editing,isAdmin:e.target.checked})} disabled={busy}/>管理员权限</label>
      <label>重置密码<input type="password" minLength={8} maxLength={256} autoComplete="new-password" placeholder="留空保留现有密码" value={resetPassword} onChange={e=>setResetPassword(e.target.value)} disabled={busy}/></label>
      <p className="warning">变更会撤销该账号的现有会话。</p>{error&&<p className="error" role="alert">{error}</p>}
      <div className="dialog-actions"><button type="button" className="button secondary" disabled={busy} onClick={()=>{setEditing(null);setResetPassword('');}}>取消</button><button className="button" disabled={busy}>{busy?'保存中…':'保存账号'}</button></div>
    </form></AccountFormDialog>}
  </div>;
}

function AccountFormDialog({title,children,onClose}:{title:string;children:ReactNode;onClose:()=>void}) {
  const ref=useRef<HTMLDialogElement>(null);
  useEffect(()=>{const previous=document.activeElement;ref.current?.showModal();return()=>{ref.current?.close();if(previous instanceof HTMLElement&&previous.isConnected)previous.focus();};},[]);
  return <dialog ref={ref} aria-label={title} onCancel={event=>{event.preventDefault();onClose();}}><div className="dialog-body settings-panel"><div className="section-title"><h2>{title}</h2><button className="text-button" type="button" autoFocus onClick={onClose}>关闭</button></div>{children}</div></dialog>;
}
