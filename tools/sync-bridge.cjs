#!/usr/bin/env node
/* NDJSON connector bridge. The host performs bounded authenticated tool calls. */
'use strict';
const fs=require('node:fs'),path=require('node:path'),readline=require('node:readline');
const C=require('../site/sync-engine.js'),A=require('../site/sync-adapters.js');
function privateWrite(file,value){const out=path.resolve(file),repo=path.resolve(__dirname,'..');if(out===repo||out.startsWith(repo+path.sep))throw new Error('PRIVATE_OUTPUT_OUTSIDE_REPOSITORY_REQUIRED');if(fs.existsSync(out)&&fs.lstatSync(out).isSymbolicLink())throw new Error('PRIVATE_OUTPUT_SYMLINK_DENIED');fs.mkdirSync(path.dirname(out),{recursive:true,mode:0o700});const temp=out+'.'+require('node:crypto').randomUUID()+'.tmp',fd=fs.openSync(temp,'wx',0o600);try{fs.writeFileSync(fd,JSON.stringify(value,null,2)+'\n');fs.fsyncSync(fd);}finally{fs.closeSync(fd);}fs.renameSync(temp,out);fs.chmodSync(out,0o600);const directory=fs.openSync(path.dirname(out),'r');try{fs.fsyncSync(directory);}finally{fs.closeSync(directory);}}
async function main(){
 const args=process.argv.slice(2),command=args.shift(),options={};
 for(let i=0;i<args.length;i+=2){if(!/^--(?:state|key-file|input|output)$/.test(args[i])||!args[i+1])throw new Error('INVALID_ARGUMENTS');options[args[i].slice(2)]=args[i+1];}
 if(!['execute','recover','recheck','export','verify'].includes(command)||!options.state||!options['key-file'])throw new Error('Use execute|recover|recheck|export|verify --state PATH --key-file PATH [--input CONFIG] [--output RECEIPT]');
 const keyPath=path.resolve(options['key-file']);if((fs.statSync(keyPath).mode&0o077)!==0)throw new Error('RECOVERY_KEY_PERMISSIONS_REQUIRED');
 const key=fs.readFileSync(keyPath,'utf8').trim();
 const {createEncryptedFileStore}=await import('./sync-file-store.mjs');
 const store=await createEncryptedFileStore({path:path.resolve(options.state),key});
 const config=options.input?JSON.parse(fs.readFileSync(options.input,'utf8')):{};
 let count=0;const pending=new Map();
 // A PTY must never echo connector readback data or private configuration.
 if(process.stdin.isTTY)require('node:child_process').spawnSync('stty',['-echo'],{stdio:['inherit','ignore','ignore']});
 const lines=readline.createInterface({input:process.stdin});
 lines.on('line',raw=>{try{const answer=JSON.parse(raw),p=pending.get(answer.requestId);if(!p)return;pending.delete(answer.requestId);clearTimeout(p.timer);if(answer.ok===true)p.resolve(answer.result);else{const e=new Error('HOST_PROVIDER_FAILED');e.code=answer.errorCode||'PROVIDER_UNAVAILABLE';p.reject(e);}}catch{/* Untrusted input never becomes a command or log. */}});
 function transport(method,params){if(!['airtable.read','airtable.update','airtable.find'].includes(method))return Promise.reject(new Error('UNSUPPORTED_PROVIDER_METHOD'));const requestId=String(++count);return new Promise((resolve,reject)=>{const timer=setTimeout(()=>{pending.delete(requestId);const e=new Error('HOST_TIMEOUT');e.code='OFFLINE';reject(e);},60000);pending.set(requestId,{resolve,reject,timer});process.stdout.write(JSON.stringify({type:'provider-request',requestId,method,args:params})+'\n');});}
 const adapters={};for(const spec of config.adapters||[]){if(!spec.alias||adapters[spec.alias])throw new Error('INVALID_ADAPTER_ALIAS');adapters[spec.alias]=A.createAirtableAdapter({...spec,transport,readMethod:'airtable.read',updateMethod:'airtable.update',findMethod:'airtable.find'});}
 const engine=C.createSyncEngine({store,adapters,actor:'authorized-connector-operator'});
 const observations={},results=[];
 if(command==='execute'){
  for(const item of config.claims||[])observations[item.alias]=await engine.observe(item.claim);
  for(const item of config.updates||[]){const source=observations[item.claimAlias];if(!source)throw new Error('MISSING_SOURCE_CLAIM');const adapter=adapters[item.provider];if(!adapter)throw new Error('MISSING_ADAPTER');const record=await adapter.read(item.externalId);if(!record)throw new Error('DESTINATION_UNAVAILABLE');const operation=await engine.prepareUpdate({entityId:source.entityId,provider:item.provider,externalId:item.externalId,expectedHash:await C.hashProjection(record),patch:item.patch,sourceEvidenceId:source.evidenceId});results.push(await engine.run(operation.id));}
 }else if(command==='recover')results.push(...await engine.recover());
 else if(command==='recheck'){if(!Array.isArray(config.operationIds)||!config.operationIds.length)throw new Error('EXPLICIT_RECHECK_SCOPE_REQUIRED');for(const id of config.operationIds)results.push(await engine.recheck(id));}
 const integrity=await engine.verifyJournal(),receipt=await engine.receipt();
 if(options.output)privateWrite(options.output,receipt);
 if(config.contextOutput){const ids=config.contextEntityIds||Object.values(observations).map(x=>x.entityId);const context=await engine.exportContext({entityIds:ids,purpose:'private'});privateWrite(config.contextOutput,context);}
 lines.close();process.stdout.write(JSON.stringify({type:'complete',journal:integrity,receiptHash:receipt.contentHash,operations:results.map(x=>({id:x.id||x.operationId,status:x.status,errorCode:x.errorCode||null})),observations:Object.values(observations).map(x=>({entityId:x.entityId,duplicate:x.duplicate,conflict:x.conflict})),limitations:['Host observations are not cryptographic proof of source truth.','Airtable metadata updates have no provider compare-and-set guarantee.']})+'\n');
}
main().catch(error=>{const safe=/^[A-Z_]+$/.test(error.code||'')?error.code:'SYNC_COMMAND_FAILED';process.stderr.write(safe+'\n');process.exitCode=1;});
