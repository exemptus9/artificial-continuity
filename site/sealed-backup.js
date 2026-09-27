/* Encrypted portable files. This does NOT encrypt browser storage. */
(function(root){
'use strict';
const FORMAT='ContinuitySealedBackup/1',ITERATIONS=600000,MAX_PLAIN=16000000,MAX_FILE=24000000;
const utf8=new TextEncoder(),fail=m=>{throw new Error(m);};
function provider(){if(!root.crypto?.subtle||!root.crypto?.getRandomValues)fail('Web Crypto is unavailable. Use HTTPS or localhost in a supported browser.');return root.crypto;}
function password(value){if(typeof value!=='string'||value.length<12||value.length>1024||!value.trim())fail('Use a unique passphrase of 12–1,024 characters. No recovery or reset is available.');}
function encode(a){let s='';for(let i=0;i<a.length;i+=32768)s+=String.fromCharCode(...a.subarray(i,i+32768));return btoa(s);}
function decode(s,max){if(typeof s!=='string'||s.length>Math.ceil(max/3)*4||!s.length||s.length%4||!/^[A-Za-z0-9+/]*={0,2}$/.test(s))fail('Invalid encrypted-file encoding.');let a;try{a=Uint8Array.from(atob(s),c=>c.charCodeAt(0));}catch{fail('Invalid encrypted-file encoding.');}if(a.length>max||encode(a)!==s)fail('Invalid encrypted-file encoding.');return a;}
function header(e){return {format:e.format,kdf:e.kdf,iterations:e.iterations,cipher:e.cipher,salt:e.salt,iv:e.iv};}
function inspect(raw){
 if(typeof raw!=='string'||utf8.encode(raw).length>MAX_FILE)fail('Encrypted backup exceeds 24 MB.');
 let e;try{e=JSON.parse(raw);}catch{fail('Not a valid encrypted-backup JSON file.');}
 if(!e||Array.isArray(e)||Object.keys(e).sort().join('|')!=='cipher|ciphertext|format|iterations|iv|kdf|salt'||e.format!==FORMAT||e.kdf!=='PBKDF2-SHA256'||e.iterations!==ITERATIONS||e.cipher!=='AES-256-GCM')fail('Unsupported encrypted-backup format or KDF parameters.');
 const salt=decode(e.salt,16),iv=decode(e.iv,12),cipher=decode(e.ciphertext,MAX_PLAIN+16);
 if(salt.length!==16||iv.length!==12||cipher.length<16)fail('Invalid encrypted-backup lengths.');return {e,salt,iv,cipher};
}
async function key(pass,salt,usage){const c=provider(),material=await c.subtle.importKey('raw',utf8.encode(pass),'PBKDF2',false,['deriveKey']);return c.subtle.deriveKey({name:'PBKDF2',salt,iterations:ITERATIONS,hash:'SHA-256'},material,{name:'AES-GCM',length:256},false,[usage]);}
async function seal(raw,pass){
 password(pass);const c=provider();if(typeof raw!=='string'||utf8.encode(raw).length>MAX_PLAIN)fail('Backup exceeds 16 MB.');
 const salt=c.getRandomValues(new Uint8Array(16)),iv=c.getRandomValues(new Uint8Array(12));
 const e={format:FORMAT,kdf:'PBKDF2-SHA256',iterations:ITERATIONS,cipher:'AES-256-GCM',salt:encode(salt),iv:encode(iv)};
 const ciphertext=await c.subtle.encrypt({name:'AES-GCM',iv,tagLength:128,additionalData:utf8.encode(JSON.stringify(header(e)))},await key(pass,salt,'encrypt'),utf8.encode(raw));
 return JSON.stringify({...e,ciphertext:encode(new Uint8Array(ciphertext))});
}
async function open(raw,pass){
 password(pass);const {e,salt,iv,cipher}=inspect(raw);let data;
 try{data=await provider().subtle.decrypt({name:'AES-GCM',iv,tagLength:128,additionalData:utf8.encode(JSON.stringify(header(e)))},await key(pass,salt,'decrypt'),cipher);}catch{fail('Could not decrypt. The passphrase is wrong or the file was changed. Nothing was restored.');}
 try{return new TextDecoder('utf-8',{fatal:true}).decode(data);}catch{fail('Decrypted file is not valid UTF-8.');}
}
const api={FORMAT,ITERATIONS,MAX_FILE,inspect,seal,open};root.SealedBackup=api;if(typeof module!=='undefined')module.exports=api;
})(globalThis);
