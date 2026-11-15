import http from 'node:http';
import https from 'node:https';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {spawn} from 'node:child_process';
import {isIPv4} from 'node:net';

const here=path.dirname(fileURLToPath(import.meta.url));
const fields=['jobs','reports','entries','templates','employees','customers','appointments','materials','services','checklists','absences','overtimeUses'];
const uuid=()=>crypto.randomUUID().toUpperCase();
const clone=v=>structuredClone(v);
const key=id=>String(id).toUpperCase();
const canonical=v=>JSON.stringify(v&&typeof v==='object'?(Array.isArray(v)?v.map(x=>JSON.parse(canonical(x))):Object.fromEntries(Object.keys(v).sort().map(k=>[k,JSON.parse(canonical(v[k]))]))):v);
const equal=(a,b)=>a===undefined||b===undefined?a===b:canonical(a)===canonical(b);
const hash=s=>crypto.createHash('sha256').update(s).digest('hex');
const loopback=ip=>ip==='127.0.0.1'||ip==='::1'||ip==='::ffff:127.0.0.1';
export const privateVPNAddress=ip=>typeof ip==='string'&&isIPv4(ip)&&ip.split('.')[0]==='100'&&Number(ip.split('.')[1])>=64&&Number(ip.split('.')[1])<=127;
const failure=(status,message,extra={})=>Object.assign(new Error(message),{status,...extra});
const text=(v,max=2000)=>typeof v==='string'&&v.length<=max;
const validID=v=>typeof v==='string'&&/^[A-Fa-f0-9]{8}-(?:[A-Fa-f0-9]{4}-){3}[A-Fa-f0-9]{12}$/.test(v);

export function emptyDatabase(){return {jobs:[],reports:[],entries:[],templates:[],employees:[],customers:[],appointments:[],materials:[],services:[],checklists:[],absences:[],overtimeUses:[],clocks:{},employee:'Salvatore Albo'};}
function normalize(db){
  const result={...db};for(const field of fields)result[field]=db[field]??[];result.clocks=db.clocks??{};delete result.clock;
  const canonicalName=name=>{if(typeof name!=='string')return name;const exact=result.employees.find(e=>e.active&&e.name===name);if(exact)return exact.name;const matches=result.employees.filter(e=>e.active&&e.name.toLowerCase().startsWith(name.toLowerCase()+' '));return matches.length===1?matches[0].name:name;};
  if(typeof result.employee==='string')result.employee=canonicalName(result.employee);
  for(const row of result.entries)row.employee=canonicalName(row.employee);
  for(const row of result.absences)row.employee=canonicalName(row.employee);
  for(const row of result.overtimeUses)row.employee=canonicalName(row.employee);
  for(const row of result.reports)row.author=canonicalName(row.author);
  if(result.clocks&&typeof result.clocks==='object'&&!Array.isArray(result.clocks))for(const alias of Object.keys(result.clocks)){const name=canonicalName(alias);if(name===alias)continue;const clock=result.clocks[alias],current=result.clocks[name];if(!current||(!current.segmentStart&&clock.segmentStart))result.clocks[name]=clock;delete result.clocks[alias];}
  return result;
}
function normalizeForUser(database,user){const result=normalize(database);if(!result.clocks||Array.isArray(result.clocks)||typeof result.clocks!=='object')return result;const employee=user.role==='worker'?result.employees.find(e=>key(e.id)===key(user.employeeID)):result.employees.find(e=>e.active&&e.name.toLowerCase()===String(user.username||'').trim().toLowerCase())||result.employees.find(e=>e.active&&e.name.toLowerCase().startsWith(String(user.username||'').trim().toLowerCase()+' '));if(!employee)return result;const alias=String(user.username||'').trim().toLowerCase();for(const clockName of Object.keys(result.clocks)){if(clockName===employee.name)continue;if(user.role==='worker'||clockName.toLowerCase()===alias||clockName.toLowerCase().startsWith(alias+' ')){const clock=result.clocks[clockName],current=result.clocks[employee.name];if(!current||(!current.segmentStart&&clock.segmentStart))result.clocks[employee.name]=clock;delete result.clocks[clockName];}}return result;}
function scopeForWorker(database,user){const scoped=clone(normalize(database)),employeeID=key(user.employeeID),employee=scoped.employees.find(e=>key(e.id)===employeeID),name=employee?.name;scoped.employees=scoped.employees.filter(e=>key(e.id)===employeeID);scoped.entries=scoped.entries.filter(e=>e.employee===name);scoped.reports=scoped.reports.filter(r=>r.author===name);scoped.appointments=scoped.appointments.filter(a=>key(a.employeeID)===employeeID);scoped.absences=scoped.absences.filter(a=>a.employee===name);scoped.overtimeUses=scoped.overtimeUses.filter(a=>a.employee===name);scoped.clocks=Object.fromEntries(Object.entries(scoped.clocks).filter(([n])=>n===name));return scoped;}
function restoreWorkerSnapshot(source,snapshot,user){const full=clone(normalize(source)),scoped=normalize(snapshot),employeeID=key(user.employeeID),employee=full.employees.find(e=>key(e.id)===employeeID),name=employee?.name;if(!employee)throw failure(403,'Mitarbeiterkonto ist nicht zugeordnet.');for(const field of ['jobs','templates','employees','customers','appointments','materials','services','checklists','absences','reports','entries','overtimeUses']){const owns=row=>field==='entries'||field==='absences'||field==='overtimeUses'?row.employee===name:field==='reports'?row.author===name:field==='appointments'?key(row.employeeID)===employeeID:false;if(['entries','reports','appointments','absences','overtimeUses'].includes(field))full[field]=[...full[field].filter(row=>!owns(row)),...scoped[field].filter(owns).map(clone)];else if(field==='employees')full[field]=[...full[field].filter(row=>key(row.id)!==employeeID),...scoped[field].filter(row=>key(row.id)===employeeID).map(clone)];else if(field==='customers'){const existing=new Set(full.customers.map(row=>key(row.id)));full.customers=[...full.customers,...scoped.customers.filter(row=>!existing.has(key(row.id))).map(clone)];}else full[field]=clone(scoped[field]);}full.clocks={...Object.fromEntries(Object.entries(full.clocks).filter(([n])=>n!==name)),...(scoped.clocks[name]?{[name]:clone(scoped.clocks[name])}:{})};return normalize(full);}
function mergeWorkerSnapshot(base,local,user){const expected=scopeForWorker(base,user);for(const field of ['jobs','templates','employees','materials','services','checklists'])if(!equal(local[field],expected[field]))throw failure(403,'Mitarbeiter dürfen '+field+' nicht bearbeiten.');const oldCustomers=new Map(expected.customers.map(row=>[key(row.id),row])),newCustomers=new Map(local.customers.map(row=>[key(row.id),row]));for(const [id,row] of oldCustomers)if(!newCustomers.has(id)||!equal(row,newCustomers.get(id)))throw failure(403,'Mitarbeiter dürfen bestehende Kunden nicht bearbeiten oder löschen.');return restoreWorkerSnapshot(base,local,user);}

export function mergeDatabase(base,local,remote){
  base=normalize(base);local=normalize(local);remote=normalize(remote);
  const merged=clone(remote),conflicts=[];
  for(const field of fields){
    const maps=[base,local,remote].map(db=>new Map(db[field].map(row=>[key(row.id),row]))),rows=[];
    for(const id of new Set(maps.flatMap(m=>[...m.keys()]))){
      const [before,mine,theirs]=maps.map(m=>m.get(id));let result;
      if(equal(mine,before))result=theirs;
      else if(equal(theirs,before)||equal(mine,theirs))result=mine;
      else{conflicts.push({collection:field,id});continue;}
      if(result!==undefined)rows.push(clone(result));
    }
    merged[field]=rows;
  }
  merged.clocks={};
  for(const name of new Set([base,local,remote].flatMap(db=>Object.keys(db.clocks)))){
    const [before,mine,theirs]=[base,local,remote].map(db=>db.clocks[name]);let result;
    if(equal(mine,before))result=theirs;
    else if(equal(theirs,before)||equal(mine,theirs))result=mine;
    else{conflicts.push({collection:'clocks',id:name});continue;}
    if(result!==undefined)merged.clocks[name]=clone(result);
  }
  if(conflicts.length)throw failure(409,'Dieselben Datensätze wurden auf mehreren Geräten geändert. Die lokalen Änderungen bleiben erhalten.',{conflicts});
  return merged;
}

function validate(db,photos,{allowMissingPhotos=false}={}){
  for(const field of fields){
    if(!Array.isArray(db[field])||db[field].length>20000)throw failure(400,'Ungültige Datensammlung: '+field);
    const ids=new Set();for(const row of db[field]){if(!row||!validID(row.id)||ids.has(key(row.id)))throw failure(400,'Ungültige oder doppelte ID in '+field);ids.add(key(row.id));}
  }
  const jobs=new Set(db.jobs.map(j=>key(j.id)));
  for(const j of db.jobs)if(!text(j.title,200)||!j.title.trim()||!text(j.customer,100)||!j.customer.trim()||!text(j.address,300)||!text(j.deliveryAddress??'',300)||typeof (j.deliveryAddressDifferent??false)!=='boolean'||(j.deliveryAddressDifferent&&!j.deliveryAddress.trim())||!['fixed','effort',undefined].includes(j.timeBillingMode)||!['Geplant','In Arbeit','Abgeschlossen'].includes(j.status)||!text(j.notes??'',5000))throw failure(400,'Ungültige Baustelle.');
  for(const r of db.reports){
    if(!jobs.has(key(r.jobID))||!text(r.title,200)||!r.title.trim()||!text(r.author,100)||!r.author.trim()||!text(r.text,30000)||!r.text.trim()||!text(r.internalNotes??'',10000)||!text(r.materials,10000)||!text(r.customerName,100)||!Number.isFinite(r.date)||typeof r.completed!=='boolean'||!Array.isArray(r.photos)||!Array.isArray(r.measurements))throw failure(400,'Ungültiger Bericht.');
    if(r.completed&&(!text(r.customerName,100)||!r.customerName.trim()||!r.customerSignature||!r.workerSignature))throw failure(400,'Abgeschlossene Berichte brauchen Kundenname und beide Unterschriften.');
    for(const signature of [r.customerSignature,r.workerSignature].filter(Boolean))if(!validImage(signature))throw failure(400,'Ungültige Unterschrift.');
    for(const p of r.photos)if(!validID(p.id)||!safePhotoName(p.filename)||(!(allowMissingPhotos&&photos[p.filename]===undefined)&&!validImage(photos[p.filename]))||!text(p.caption,1000))throw failure(400,'Foto fehlt oder ist ungültig.');
    for(const m of r.measurements)if(!validID(m.id)||!text(m.label,200)||![m.length,m.width].every(v=>Number.isFinite(v)&&v>0)||!Number.isInteger(m.quantity)||m.quantity<1||m.quantity>999||!Number.isFinite(m.length*m.width*m.quantity))throw failure(400,'Ungültiges Aufmaß.');
    if(r.positions!==undefined&&(!Array.isArray(r.positions)||r.positions.length>1000||r.positions.some(p=>!p||!['Material','Leistung'].includes(p.kind)||!text(p.name,200)||!p.name.trim()||!text(p.unit??'',40)||!Number.isFinite(p.price)||p.price<0||!Number.isFinite(p.quantity)||p.quantity<=0)))throw failure(400,'Ungültige Material- oder Leistungsposition.');
    if(r.checklistIDs!==undefined&&(!Array.isArray(r.checklistIDs)||r.checklistIDs.some(id=>!validID(id))))throw failure(400,'Ungültige Checklisten-Zuordnung.');
  }
  const validBreaks=(breaks,start,end)=>breaks===undefined||(Array.isArray(breaks)&&breaks.length<=100&&breaks.every(b=>b&&Number.isFinite(b.start)&&Number.isFinite(b.end)&&b.start>=start&&b.end>=b.start&&b.end<=end));
  for(const e of db.entries)if((e.jobID!=null&&!jobs.has(key(e.jobID)))||![e.start,e.end].every(Number.isFinite)||e.end<e.start||!text(e.employee??'',100)||(e.paidSeconds!==undefined&&(!Number.isFinite(e.paidSeconds)||e.paidSeconds<0||e.paidSeconds>e.end-e.start+1))||!validBreaks(e.breaks,e.start,e.end))throw failure(400,'Ungültige Arbeitszeit.');
  for(const t of db.templates)if(!text(t.name,100)||!t.name.trim()||!text(t.text,30000))throw failure(400,'Ungültige Vorlage.');
  const names=new Set();for(const e of db.employees){if(!text(e.name,100)||!e.name.trim()||names.has(e.name)||!text(e.role,100)||typeof e.active!=='boolean')throw failure(400,'Ungültiger oder doppelter Mitarbeiter.');names.add(e.name);}
  for(const c of db.customers)if(!text(c.name,150)||!c.name.trim()||!text(c.address,300)||!text(c.phone??'',100)||!text(c.landline??'',100)||!text(c.mobile??'',100)||!text(c.email,200)||!text(c.customerNumber??'',100)||!text(c.firstName??'',100)||!text(c.lastName??'',100)||!text(c.companyName??'',150)||!['Privatkunde','Firmenkunde',undefined].includes(c.type))throw failure(400,'Ungültiger Kunde.');
  for(const a of db.appointments)if(!text(a.customer,100)||!a.customer.trim()||!text(a.title??'',200)||!text(a.deliveryAddress,300)||!Number.isFinite(a.date)||(a.employeeID!==undefined&&!db.employees.some(e=>key(e.id)===key(a.employeeID)&&e.active))||(a.jobID!==undefined&&!jobs.has(key(a.jobID))))throw failure(400,'Ungültiger Termin.');
  for(const field of ['materials','services'])for(const item of db[field])if(!text(item.name,200)||!item.name.trim()||!Number.isFinite(item.price)||item.price<0)throw failure(400,'Ungültiger Katalogeintrag.');
  for(const list of db.checklists)if(!text(list.name,100)||!list.name.trim()||!Array.isArray(list.items)||list.items.some(item=>!text(item,300)))throw failure(400,'Ungültige Checkliste.');
  for(const absence of db.absences)if(!['Krankmeldung','Urlaub'].includes(absence.type)||!text(absence.employee,100)||!Number.isFinite(absence.start)||!Number.isFinite(absence.end)||absence.end<absence.start)throw failure(400,'Ungültige Abwesenheit.');
  for(const use of db.overtimeUses)if(!db.employees.some(e=>e.name===use.employee)||!Number.isFinite(use.date)||!Number.isFinite(use.hours)||use.hours<=0||use.hours>24||!text(use.note??'',500))throw failure(400,'Ungültiger Überstundenabbau.');
  if(!db.clocks||Array.isArray(db.clocks)||typeof db.clocks!=='object')throw failure(400,'Ungültige Zeiterfassung.');
  for(const [name,c] of Object.entries(db.clocks)){if(!db.employees.some(e=>e.name===name&&e.active))throw failure(400,`Ungültige laufende Zeiterfassung: Mitarbeiter „${name}“ ist nicht aktiv zugeordnet.`);if(c.jobID!=null&&!jobs.has(key(c.jobID)))throw failure(400,`Ungültige laufende Zeiterfassung für ${name}: Baustelle fehlt.`);if(!Number.isFinite(c.started)||!Number.isFinite(c.accumulated)||c.accumulated<0||(c.segmentStart!==undefined&&c.segmentStart!==null&&!Number.isFinite(c.segmentStart))||(c.pauseStarted!==undefined&&(!Number.isFinite(c.pauseStarted)||c.pauseStarted<c.started))||!validBreaks(c.breaks,c.started,Number.MAX_SAFE_INTEGER))throw failure(400,`Ungültige laufende Zeiterfassung für ${name}: Zeitwerte sind ungültig.`);}
}
function safePhotoName(name){return typeof name==='string'&&/^[A-Fa-f0-9-]{36}\.(?:jpg|png)$/.test(name)&&validID(name.slice(0,36));}
function validImage(value){if(typeof value!=='string'||value.length>12*1024*1024||!/^[a-zA-Z0-9+/]*={0,2}$/.test(value))return false;const bytes=Buffer.from(value,'base64');return bytes.length>8&&((bytes[0]===255&&bytes[1]===216&&bytes[2]===255)||bytes.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10])));}
function logoMedia(data){
  if(typeof data!=='string'||data.length>14*1024*1024||!/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(data))return null;
  const bytes=Buffer.from(data,'base64');if(bytes.length<12||bytes.length>10*1024*1024||bytes.toString('base64')!==data)return null;
  if(bytes.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10])))return {mime:'image/png',size:bytes.length};
  if(bytes[0]===255&&bytes[1]===216&&bytes[2]===255)return {mime:'image/jpeg',size:bytes.length};
  if(bytes.subarray(0,4).toString('ascii')==='RIFF'&&bytes.subarray(8,12).toString('ascii')==='WEBP')return {mime:'image/webp',size:bytes.length};
  return null;
}
function workerMayChange(user,before,after){
  if(user.role==='admin')return;
  const employee=before.employees.find(e=>key(e.id)===key(user.employeeID));
  if(!employee?.active)throw failure(403,'Mitarbeiterzugang ist nicht aktiv.');
  for(const field of ['jobs','templates','employees','materials','services','checklists'])if(!equal(before[field],after[field]))throw failure(403,'Nur die Geschäftsführung darf '+field+' bearbeiten.');
  for(const field of ['customers','appointments','absences','overtimeUses']){
    const b=new Map(before[field].map(r=>[key(r.id),r])),a=new Map(after[field].map(r=>[key(r.id),r]));
    for(const id of new Set([...a.keys(),...b.keys()]))if(!equal(a.get(id),b.get(id))){
      const old=b.get(id),row=a.get(id);
      if(old||!row)throw failure(403,'Mitarbeiter dürfen bestehende '+field+' nicht bearbeiten oder löschen.');
      if(field==='absences'&&row.employee!==employee.name)throw failure(403,'Abwesenheiten dürfen nur für das eigene Konto erfasst werden.');
      if(field==='overtimeUses'&&row.employee!==employee.name)throw failure(403,'Überstundenabbau darf nur für das eigene Konto erfasst werden.');
      if(field==='appointments'&&key(row.employeeID)!==key(employee.id))throw failure(403,'Mitarbeiter dürfen Termine nur sich selbst zuordnen.');
    }
  }
  for(const field of ['reports','entries']){
    const b=new Map(before[field].map(r=>[key(r.id),r])),a=new Map(after[field].map(r=>[key(r.id),r]));
    for(const id of new Set([...a.keys(),...b.keys()]))if(!equal(a.get(id),b.get(id))){
      for(const row of [a.get(id),b.get(id)].filter(Boolean))if((field==='reports'?row.author:row.employee)!==employee.name)throw failure(403,'Fremde Berichte und Arbeitszeiten dürfen nicht geändert werden.');
    }
  }
  for(const name of new Set([...Object.keys(before.clocks),...Object.keys(after.clocks)]))if(name!==employee.name&&!equal(before.clocks[name],after.clocks[name]))throw failure(403,'Fremde Zeiterfassung darf nicht geändert werden.');
}

export function createWorkspaceServer({dataDir=path.join(here,'data'),host='127.0.0.1',port=8787,tls,allowInsecureLAN=false,vpnHost,vpnPort=8788}={}){
  if(vpnHost&&!privateVPNAddress(vpnHost))throw Error('Für die private VPN-Verbindung ist die NetBird-Adresse des PCs aus 100.64.0.0/10 erforderlich.');
  if(vpnHost&&(!loopback(host)||tls))throw Error('Die lokale Verwaltung muss im privaten VPN-Modus ausschließlich auf localhost laufen.');
  if(!loopback(host)&&!tls&&!allowInsecureLAN)throw Error('Für Netzwerkbetrieb HTTPS konfigurieren oder den unverschlüsselten LAN-Test ausdrücklich mit --allow-insecure-lan aktivieren.');
  fs.mkdirSync(dataDir,{recursive:true});const filename=path.join(dataDir,'workspace.json');
  let state;
  if(fs.existsSync(filename)){
    state=JSON.parse(fs.readFileSync(filename,'utf8'));
    if(state.schema!==1||!Array.isArray(state.history)||!Array.isArray(state.keys))throw Error('Datenbankformat nicht lesbar. Vorhandene Datei wird nicht überschrieben.');
    if(state.logo!==undefined&&state.logo!==null&&(!state.logo||typeof state.logo.name!=='string'||!logoMedia(state.logo.data)||logoMedia(state.logo.data).mime!==state.logo.mime))throw Error('Gespeicherte Logo-Datei ist ungültig. Vorhandene Datei wird nicht überschrieben.');
    const hadUsers=Object.hasOwn(state,'users');
    if(!hadUsers)state.users=[];
    else if(!Array.isArray(state.users)||state.users.some(u=>!u||!validID(u.id)||typeof u.username!=='string'||! /^[A-Za-z0-9._-]{3,40}$/.test(u.username)||!['admin','worker'].includes(u.role)||typeof u.active!=='boolean'||typeof u.salt!=='string'||!/^[a-f0-9]{32}$/.test(u.salt)||typeof u.passwordHash!=='string'||!/^[a-f0-9]{128}$/.test(u.passwordHash)))throw Error('Benutzerkonten sind beschädigt. Vorhandene Datei wird nicht überschrieben.');
    state._needsUserMigration=!hadUsers;
    validate(normalize(state.database),state.photos);
  }else{
    const database=emptyDatabase();database.employees=[{id:'A1B00000-0000-4000-8000-000000000001',name:'Salvatore Albo',role:'Monteur',active:true}];
    database.templates=[
      {id:'A1B00000-0000-4000-8000-000000001001',name:'Wartung',text:'Anlage / Gerät:\nDurchgeführte Wartung:\nMesswerte:\nFestgestellte Mängel:\nEmpfohlene Maßnahmen:'},
      {id:'A1B00000-0000-4000-8000-000000001002',name:'Reparatur',text:'Gemeldete Störung:\nUrsache:\nDurchgeführte Arbeiten:\nFunktionsprüfung:\nWeitere Maßnahmen:'},
      {id:'A1B00000-0000-4000-8000-000000001003',name:'Kundendienst',text:'Kundenauftrag:\nDurchgeführte Arbeiten:\nErgebnis / Übergabe:\nOffene Punkte:'}
    ];
    state={schema:1,workspaceID:uuid(),revision:0,database,photos:{},keys:[],users:[],signingKey:crypto.randomBytes(32).toString('hex'),history:[{revision:0,database:clone(database)}]};
  }
  function commit(next){const temporary=filename+'.'+crypto.randomUUID()+'.tmp';const fd=fs.openSync(temporary,'wx',0o600);try{fs.writeFileSync(fd,JSON.stringify(next));fs.fsyncSync(fd);}finally{fs.closeSync(fd);}try{fs.renameSync(temporary,filename);}catch(e){try{fs.unlinkSync(temporary);}catch{}throw e;}state=next;}
  const needsUserMigration=state._needsUserMigration===true;delete state._needsUserMigration;
  if(!fs.existsSync(filename)||needsUserMigration)commit(state);
  if(!state.signingKey){const next=clone(state);next.signingKey=crypto.randomBytes(32).toString('hex');commit(next);}
  const sessions=new Map();
  const loginAttempts=new Map();
  let setupInProgress=false;
  const proof=(revision,database)=>crypto.createHmac('sha256',state.signingKey).update(canonical({workspaceID:state.workspaceID,revision,database})).digest('hex');
  const scopedProof=(revision,database,user)=>crypto.createHmac('sha256',state.signingKey).update(canonical({workspaceID:state.workspaceID,revision,employeeID:user.employeeID,database})).digest('hex');
  const view=()=>({workspaceID:state.workspaceID,revision:state.revision,proof:proof(state.revision,state.database),backup:{version:1,database:state.database,photos:state.photos}});
  const userView=user=>{const result=view();if(user.role==='admin')return result;const database=scopeForWorker(result.backup.database,user),files=new Set(database.reports.flatMap(r=>r.photos.map(p=>p.filename)));return {...result,proof:scopedProof(result.revision,database,user),backup:{...result.backup,database,photos:Object.fromEntries(Object.entries(result.backup.photos).filter(([name])=>files.has(name)))}};};
  const respond=(res,status,body,headers={})=>{res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff',...headers});res.end(JSON.stringify(body));};
  async function body(req){let size=0;const chunks=[];for await(const chunk of req){size+=chunk.length;if(size>64*1024*1024)throw failure(413,'Datenpaket ist größer als 64 MB.');chunks.push(chunk);}try{return JSON.parse(Buffer.concat(chunks).toString('utf8'));}catch{throw failure(400,'Ungültige JSON-Daten.');}}
  function authorized(req,remote=false){
    const bearer=/^Bearer ([a-f0-9]{64})$/i.exec(req.headers.authorization||'');
    if(bearer){const result=state.keys.find(k=>k.hash===hash(bearer[1]));if(result){const employeeID=result.employeeID??state.database.employees.find(e=>e.active)?.id;if(!state.database.employees.some(e=>key(e.id)===key(employeeID)&&e.active))throw failure(403,'Mitarbeiterzugang ist nicht aktiv.');return {...result,role:'worker',employeeID};}}
    if(remote)throw failure(401,'Für den Baustellenzugriff ist ein eigener Kopplungsschlüssel erforderlich.');
    const cookie=/(?:^|;\s*)alboSession=([a-f0-9]{64})(?:;|$)/.exec(req.headers.cookie||'');const session=cookie&&sessions.get(cookie[1]);
    if(session&&session.expires>Date.now()){const account=state.users.find(u=>u.id===session.userID&&u.active);if(account){if(account.role==='worker'&&!state.database.employees.some(e=>key(e.id)===key(account.employeeID)&&e.active))throw failure(403,'Mitarbeiterzugang ist nicht aktiv.');return account;}}
    throw failure(401,'Bitte anmelden oder Gerät koppeln.');
  }
  const passwordRecord=password=>{const salt=crypto.randomBytes(16).toString('hex');return {salt,passwordHash:crypto.scryptSync(password,salt,64).toString('hex')};};
  const validUsername=name=>typeof name==='string'&&/^[A-Za-z0-9._-]{3,40}$/.test(name);
  const validPassword=password=>typeof password==='string'&&password.length>=12&&password.length<=200;
  const safeUser=({passwordHash,salt,...user})=>user;
  const cookieHeader=(token,maxAge)=>`alboSession=${token}; HttpOnly; SameSite=Strict; Path=/; Max-Age=${maxAge}${tls?'; Secure':''}`;
  const newSession=(res,user)=>{const token=crypto.randomBytes(32).toString('hex');sessions.set(token,{userID:user.id,expires:Date.now()+8*3600000});return {'Set-Cookie':cookieHeader(token,28800)};};
  const handler=async(req,res,remote=false)=>{
    try{
      const url=new URL(req.url,'http://localhost');
      if(remote&&!((req.method==='GET'&&url.pathname==='/api/workspace')||(req.method==='POST'&&url.pathname==='/api/sync')||(req.method==='POST'&&url.pathname==='/api/mobile-login')))throw failure(403,'Über die Baustellenverbindung ist nur Anmeldung und Datenabgleich freigegeben.');
      if(req.headers.origin&&req.headers.origin!==`${tls?'https':'http'}://${req.headers.host}`)throw failure(403,'Anfrage von fremdem Ursprung abgelehnt.');
      if(req.headers['sec-fetch-site']==='cross-site')throw failure(403,'Fremde Website darf nicht auf diese Verwaltung zugreifen.');
      if(req.method==='GET'&&url.pathname==='/api/auth-status')return respond(res,200,{setupRequired:state.users.length===0});
      if(req.method==='POST'&&url.pathname==='/api/setup'){
        if(!loopback(req.socket.remoteAddress)||state.users.length||setupInProgress)throw failure(403,'Die erste Benutzeranlage ist nur einmal direkt auf diesem PC möglich.');
        const hostName=(req.headers.host||'').toLowerCase();if(!/^(localhost|127\.0\.0\.1|\[::1\])(?::\d+)?$/.test(hostName))throw failure(403,'Bitte die Verwaltung über localhost öffnen.');
        setupInProgress=true;try{const request=await body(req);if(state.users.length)throw failure(403,'Das Administratorkonto wurde bereits eingerichtet.');if(!validUsername(request.username)||!validPassword(request.password))throw failure(400,'Benutzername (3–40 Zeichen) und Passwort (mindestens 12 Zeichen) eingeben.');
          const user={id:uuid(),username:request.username,role:'admin',employeeID:null,active:true,created:new Date().toISOString(),...passwordRecord(request.password)};
          const next=clone(state);next.users=[user];commit(next);return respond(res,201,{user:safeUser(user)},newSession(res,user));
        }finally{setupInProgress=false;}
      }
      if(req.method==='POST'&&url.pathname==='/api/login'){
        const ip=req.socket.remoteAddress||'unknown',attempt=loginAttempts.get(ip)||{count:0,until:0};if(attempt.until>Date.now())throw failure(429,'Zu viele Anmeldeversuche. Bitte später erneut versuchen.');
        const request=await body(req),username=typeof request.username==='string'?request.username.toLowerCase():'';
        const user=state.users.find(u=>u.username.toLowerCase()===username&&u.active);
        const supplied=typeof request.password==='string'?request.password.slice(0,200):'';
        const candidate=crypto.scryptSync(supplied,user?.salt||'albo-invalid-user-salt',64);
        const expected=Buffer.from(user?.passwordHash||'0'.repeat(128),'hex');
        const matches=typeof request.password==='string'&&request.password.length<=200&&request.password.length>=12&&expected.length===candidate.length&&crypto.timingSafeEqual(candidate,expected);
        if(!user||!matches){attempt.count++;if(attempt.count>=8){attempt.count=0;attempt.until=Date.now()+15*60*1000;}loginAttempts.set(ip,attempt);throw failure(401,'Benutzername oder Passwort ist falsch.');}
        if(user.role==='worker'&&!state.database.employees.some(e=>key(e.id)===key(user.employeeID)&&e.active))throw failure(403,'Mitarbeiterzugang ist nicht aktiv.');
        loginAttempts.delete(ip);return respond(res,200,{user:safeUser(user)},newSession(res,user));
      }
      if(req.method==='POST'&&url.pathname==='/api/mobile-login'){
        const remoteIP=(req.socket.remoteAddress||'').replace(/^::ffff:/,'');
        if(!remote||!privateVPNAddress(remoteIP))throw failure(403,'Die mobile Kontoanmeldung ist nur über die private NetBird-Verbindung erlaubt.');
        const ip=remoteIP||'unknown',attempt=loginAttempts.get(ip)||{count:0,until:0};if(attempt.until>Date.now())throw failure(429,'Zu viele Anmeldeversuche. Bitte später erneut versuchen.');
        const request=await body(req),username=typeof request.username==='string'?request.username.trim().toLowerCase():'';
        const user=state.users.find(u=>u.username.toLowerCase()===username&&u.active);
        const supplied=typeof request.password==='string'?request.password.slice(0,200):'';
        const candidate=crypto.scryptSync(supplied,user?.salt||'albo-invalid-user-salt',64),expected=Buffer.from(user?.passwordHash||'0'.repeat(128),'hex');
        const matches=typeof request.password==='string'&&request.password.length<=200&&request.password.length>=12&&expected.length===candidate.length&&crypto.timingSafeEqual(candidate,expected);
        if(!user||!matches){attempt.count++;if(attempt.count>=8){attempt.count=0;attempt.until=Date.now()+15*60*1000;}loginAttempts.set(ip,attempt);throw failure(401,'Benutzername oder Passwort ist falsch.');}
        if(user.role!=='worker'||!user.employeeID)throw failure(403,'Die mobile Anmeldung ist für Mitarbeiterkonten vorgesehen.');
        if(!state.database.employees.some(e=>e.active&&key(e.id)===key(user.employeeID)))throw failure(403,'Mitarbeiterzugang ist nicht aktiv.');
        const token=crypto.randomBytes(32).toString('hex'),record={id:uuid(),hash:hash(token),role:'worker',label:`Android · ${user.username}`,employeeID:user.employeeID,userID:user.id,created:new Date().toISOString()};
        const next=clone(state);next.keys.push(record);commit(next);loginAttempts.delete(ip);
        return respond(res,200,{token,identity:{username:user.username,role:user.role,employeeID:user.employeeID}});
      }
      if(req.method==='POST'&&url.pathname==='/api/logout'){
        const cookie=/(?:^|;\s*)alboSession=([a-f0-9]{64})(?:;|$)/.exec(req.headers.cookie||'');if(cookie)sessions.delete(cookie[1]);
        return respond(res,200,{ok:true},{'Set-Cookie':cookieHeader('',0)});
      }
      if(url.pathname.startsWith('/api/')){
        const user=authorized(req,remote);
        if(req.method==='GET'&&url.pathname==='/api/workspace')return respond(res,200,{...userView(user),identity:{username:user.username??user.label??'Gerät',role:user.role,employeeID:user.employeeID??null}});
        if(req.method==='GET'&&url.pathname==='/api/logo')return respond(res,200,{logo:state.logo?{name:state.logo.name,mime:state.logo.mime,size:state.logo.size,updated:state.logo.updated}:null});
        if(req.method==='GET'&&url.pathname==='/api/logo-image'){
          if(!state.logo||!state.logo.mime.startsWith('image/'))throw failure(404,'Kein Bildlogo gespeichert.');const bytes=Buffer.from(state.logo.data,'base64');
          res.writeHead(200,{'Content-Type':state.logo.mime,'Content-Length':bytes.length,'Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Content-Security-Policy':"default-src 'none'; sandbox"});res.end(bytes);return;
        }
        if(req.method==='POST'&&url.pathname==='/api/logo'){
          if(user.role!=='admin')throw failure(403,'Nur Verwaltung darf ein Logo hochladen.');const request=await body(req),media=logoMedia(request.data);
          if(!media)throw failure(400,'Logo-Datei ungültig. Erlaubt sind PNG, JPG oder WebP bis 10 MB.');
          if(request.mime&&request.mime!==media.mime)throw failure(400,'Dateityp stimmt nicht mit der Logo-Datei überein.');
          const name=typeof request.name==='string'?request.name.replace(/[\\/:*?"<>|\u0000-\u001f]/g,'').trim().slice(0,120):'';if(!name)throw failure(400,'Dateiname fehlt.');
          const next=clone(state);next.logo={name,mime:media.mime,size:media.size,updated:new Date().toISOString(),data:request.data};commit(next);return respond(res,201,{logo:{name,mime:media.mime,size:media.size,updated:next.logo.updated}});
        }
        if(req.method==='GET'&&url.pathname==='/api/users'){if(user.role!=='admin'||!user.username)throw failure(403,'Nur ein PC-Administratorkonto darf Benutzer verwalten.');return respond(res,200,{users:state.users.map(safeUser)});}
        if(req.method==='POST'&&url.pathname==='/api/users'){
          if(user.role!=='admin'||!user.username)throw failure(403,'Nur ein PC-Administratorkonto darf Benutzer anlegen.');const request=await body(req),username=typeof request.username==='string'?request.username.trim():'';
          if(!validUsername(username)||!validPassword(request.password)||request.role!=='worker')throw failure(400,'Benutzername, mindestens 12-stelliges Passwort und die Rolle Mitarbeiter eingeben. Nur das erste Geschäftsführungskonto hat Vollzugriff.');
          if(state.users.some(u=>u.username.toLowerCase()===username.toLowerCase()))throw failure(409,'Dieser Benutzername ist bereits vergeben.');
          if(request.role==='worker'&&!state.database.employees.some(e=>e.active&&key(e.id)===key(request.employeeID)))throw failure(400,'Aktiven Mitarbeiter auswählen.');
          const account={id:uuid(),username,role:request.role,employeeID:request.role==='worker'?request.employeeID:null,active:true,created:new Date().toISOString(),...passwordRecord(request.password)};const next=clone(state);next.users.push(account);commit(next);return respond(res,201,{user:safeUser(account)});
        }
        if(req.method==='PUT'&&url.pathname.startsWith('/api/users/')){
          if(user.role!=='admin'||!user.username)throw failure(403,'Nur ein PC-Administratorkonto darf Benutzer bearbeiten.');const id=decodeURIComponent(url.pathname.slice(11)),request=await body(req),next=clone(state),account=next.users.find(u=>u.id===id);if(!account)throw failure(404,'Benutzer nicht gefunden.');
          const username=typeof request.username==='string'?request.username.trim():'';if(!validUsername(username)||!['admin','worker'].includes(request.role)||typeof request.active!=='boolean'||(request.role==='admin'&&account.role!=='admin'))throw failure(400,'Benutzername, Rolle und Status prüfen. Nur das bestehende Geschäftsführungskonto behält Vollzugriff.');
          if(next.users.some(u=>u.id!==id&&u.username.toLowerCase()===username.toLowerCase()))throw failure(409,'Dieser Benutzername ist bereits vergeben.');
          if(request.role==='worker'&&!next.database.employees.some(e=>e.active&&key(e.id)===key(request.employeeID)))throw failure(400,'Aktiven Mitarbeiter auswählen.');
          if(request.password!==undefined&&!validPassword(request.password))throw failure(400,'Das neue Passwort muss mindestens 12 Zeichen lang sein.');
          if(user.id===id&&(!request.active||request.role!=='admin'))throw failure(400,'Das eigene Administratorkonto kann nicht deaktiviert oder herabgestuft werden.');
           account.username=username;account.role=request.role;account.employeeID=request.role==='worker'?request.employeeID:null;account.active=request.active;if(request.password){Object.assign(account,passwordRecord(request.password));}
          if(!next.users.some(u=>u.active&&u.role==='admin'))throw failure(400,'Mindestens ein aktives Administratorkonto muss bestehen bleiben.');
           const ownToken=/(?:^|;\s*)alboSession=([a-f0-9]{64})(?:;|$)/.exec(req.headers.cookie||'')?.[1];if(!account.active||request.password)next.keys=next.keys.filter(k=>k.userID!==id);commit(next);for(const [token,session] of sessions)if(session.userID===id&&(!account.active||(request.password&&token!==ownToken)))sessions.delete(token);return respond(res,200,{user:safeUser(account)});
         }
        if(req.method==='GET'&&url.pathname==='/api/keys'){if(user.role!=='admin')throw failure(403,'Nur Verwaltung.');return respond(res,200,{keys:state.keys.map(({hash,...rest})=>rest)});}
        if(req.method==='POST'&&url.pathname==='/api/keys'){
          if(user.role!=='admin')throw failure(403,'Nur Verwaltung darf Geräte koppeln.');const request=await body(req);
          if(request.role!=='worker'||!text(request.label,100)||!request.label.trim())throw failure(400,'Gerätename eingeben. Nur Mitarbeitergeräte können gekoppelt werden; Vollzugriff bleibt beim PC-Geschäftsführungskonto.');
          if(request.role==='worker'&&!state.database.employees.some(e=>e.active&&key(e.id)===key(request.employeeID)))throw failure(400,'Aktiven Mitarbeiter auswählen.');
          const token=crypto.randomBytes(32).toString('hex'),record={id:uuid(),hash:hash(token),role:request.role,label:request.label,employeeID:request.employeeID??null,created:new Date().toISOString()};
          const next=clone(state);next.keys.push(record);commit(next);return respond(res,201,{token,id:record.id});
        }
        if(req.method==='DELETE'&&url.pathname.startsWith('/api/keys/')){if(user.role!=='admin')throw failure(403,'Nur Verwaltung.');const next=clone(state);next.keys=next.keys.filter(k=>k.id!==decodeURIComponent(url.pathname.slice(10)));commit(next);return respond(res,200,{ok:true});}
        if(req.method==='POST'&&url.pathname==='/api/sync'){
          const request=await body(req);
          if(request.workspaceID!==state.workspaceID)throw failure(409,'Anderer Arbeitsbereich. Bitte die Verbindung prüfen.');
          if(!request.backup||request.backup.version!==1||!request.backup.photos||Array.isArray(request.backup.photos)||typeof request.backup.photos!=='object'||!(request.baseRevision===null||Number.isInteger(request.baseRevision)))throw failure(400,'Ungültiges Synchronisierungspaket.');
          if(request.baseRevision===null&&user.role!=='admin')throw failure(403,'Vor der ersten Mitarbeitersynchronisierung die PC-Daten laden.');
          let base=request.baseRevision===null?{database:emptyDatabase()}:state.history.find(v=>v.revision===request.baseRevision);
          if(request.baseSnapshot){
            const candidate=request.baseSnapshot;
            if(candidate.revision!==request.baseRevision||typeof candidate.proof!=='string'||!/^[a-f0-9]{64}$/.test(candidate.proof)||!candidate.database)throw failure(400,'Ungültiger Ausgangsstand.');
            const expected=user.role==='admin'?proof(candidate.revision,candidate.database):scopedProof(candidate.revision,candidate.database,user);
            if(!crypto.timingSafeEqual(Buffer.from(expected,'hex'),Buffer.from(candidate.proof,'hex')))throw failure(403,'Der Ausgangsstand konnte nicht bestätigt werden. Lokale Änderungen bleiben erhalten.');
            const source=state.history.find(v=>v.revision===candidate.revision)?.database||state.database;base={database:user.role==='admin'?candidate.database:restoreWorkerSnapshot(source,candidate.database,user)};
          }
          if(!base)throw failure(409,'Der Ausgangsstand ist nicht mehr verfügbar. Bitte lokale Daten zuerst sichern und mit der Verwaltung abgleichen.');
          let local=normalizeForUser(request.backup.database||{},user);const current=normalize(state.database);
          const photos={...state.photos};for(const [name,bytes] of Object.entries(request.backup.photos)){if(!safePhotoName(name)||!validImage(bytes))throw failure(400,'Ungültiger Fotoanhang.');if(photos[name]&&photos[name]!==bytes)throw failure(409,'Foto-ID wurde mit anderem Inhalt verwendet.');photos[name]=bytes;}
          validate(local,photos,{allowMissingPhotos:true});
          // Compare the worker's edits to their own starting snapshot, not to newer remote edits.
          if(user.role!=='admin')local=mergeWorkerSnapshot(normalize(base.database),local,user);
          workerMayChange(user,normalize(base.database),local);
          const database=mergeDatabase(base.database,local,current);validate(database,photos);
          if(!database.employees.some(e=>e.active))throw failure(400,'Mindestens ein aktiver Mitarbeiter ist erforderlich.');
          const referenced=new Set(database.reports.flatMap(r=>r.photos.map(p=>p.filename)));
          const next=clone(state);next.database=database;next.photos=Object.fromEntries(Object.entries(photos).filter(([name])=>referenced.has(name)));next.revision++;next.history.push({revision:next.revision,database:clone(database)});next.history=next.history.slice(-30);commit(next);
          return respond(res,200,userView(user));
        }
        throw failure(404,'API nicht gefunden.');
      }
      const resources={'/':['index.html','text/html; charset=utf-8'],'/app.js':['app.js','application/javascript; charset=utf-8'],'/lucide.js':['vendor/lucide.min.js','application/javascript; charset=utf-8'],'/style.css':['style.css','text/css; charset=utf-8'],'/reference.png':['../ALBOConnect/Assets.xcassets/Reference.imageset/reference.png','image/png']};
      if(req.method!=='GET'||!resources[url.pathname])throw failure(404,'Seite nicht gefunden.');
      const [file,type]=resources[url.pathname];res.writeHead(200,{'Content-Type':type,'X-Content-Type-Options':'nosniff','Content-Security-Policy':"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'",'Cache-Control':'no-store'});res.end(fs.readFileSync(path.join(here,file)));
    }catch(error){respond(res,error.status||500,{error:error.status?error.message:'Speichern oder Verarbeiten fehlgeschlagen. Vorhandene Daten bleiben erhalten.',...(error.conflicts?{conflicts:error.conflicts}:{})});}
  };
  const localHandler=(req,res)=>handler(req,res,false),remoteHandler=(req,res)=>handler(req,res,true);
  const server=tls?https.createServer(tls,localHandler):http.createServer(localHandler);server.requestTimeout=30000;
  const vpnServer=vpnHost?http.createServer(remoteHandler):null;if(vpnServer)vpnServer.requestTimeout=30000;
  const start=(listener,p,h)=>new Promise((resolve,reject)=>{listener.once('error',reject);listener.listen(p,h,()=>resolve(listener.address()));});
  const stop=listener=>new Promise(resolve=>listener?.listening?listener.close(resolve):resolve());
  return {server,vpnServer,remoteHandler,async listen(){try{const address=await start(server,port,host);if(vpnServer)address.vpn=await start(vpnServer,vpnPort,vpnHost);return address;}catch(error){await Promise.all([stop(server),stop(vpnServer)]);throw error;}},async close(){await Promise.all([stop(server),stop(vpnServer)]);},snapshot:()=>clone(view())};
}

if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  const lan=process.argv.includes('--lan'),allowInsecureLAN=process.argv.includes('--allow-insecure-lan');
  const privateVPN=process.argv.includes('--private-vpn');
  if(privateVPN&&!process.env.ALBO_VPN_IP)throw Error('Die NetBird-IP des Büro-PCs fehlt. Starte Start-Baustelle-Windows.cmd.');
  if(privateVPN&&lan)throw Error('--private-vpn nicht zusammen mit --lan verwenden.');
  const tls=process.env.ALBO_TLS_KEY&&process.env.ALBO_TLS_CERT?{key:fs.readFileSync(process.env.ALBO_TLS_KEY),cert:fs.readFileSync(process.env.ALBO_TLS_CERT)}:undefined;
  const app=createWorkspaceServer({dataDir:process.env.ALBO_DATA_DIR||path.join(here,'data'),host:lan?'0.0.0.0':'127.0.0.1',port:Number(process.env.ALBO_PORT||8787),tls,allowInsecureLAN,vpnHost:privateVPN?process.env.ALBO_VPN_IP:undefined});
  const address=await app.listen();console.log(`ALBO Connect PC-Verwaltung: ${tls?'https':'http'}://localhost:${address.port}`);
  if(address.vpn)console.log(`Private Baustellenverbindung: http://${address.vpn.address}:${address.vpn.port} (nur bei aktiver VPN-Verbindung)`);
  if(process.argv.includes('--open')){
    const url=`${tls?'https':'http'}://localhost:${address.port}`;
    if(process.platform==='win32')spawn('rundll32.exe',['url.dll,FileProtocolHandler',url],{detached:true,stdio:'ignore',windowsHide:true}).unref();
    else if(process.platform==='darwin')spawn('open',[url],{detached:true,stdio:'ignore'}).unref();
  }
  if(lan&&!tls)console.log('Unverschlüsselter LAN-Test aktiviert. Nur in einem vertrauenswürdigen Testnetz verwenden.');
}
