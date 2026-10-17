import http from 'node:http';
import {generateKeyPairSync, randomUUID, createHash, sign} from 'node:crypto';
import {writeFileSync,mkdirSync} from 'node:fs';
mkdirSync('/tmp/issue108-native',{recursive:true,mode:0o700});
const {privateKey,publicKey}=generateKeyPairSync('rsa',{modulusLength:2048});
writeFileSync('/tmp/issue108-native/service-private.pem',privateKey.export({type:'pkcs8',format:'pem'}),{mode:0o600});
const jwk={...publicKey.export({format:'jwk'}),kid:'issue108-local',use:'sig',alg:'RS256'};
writeFileSync('/tmp/issue108-native/service-jwks.json',JSON.stringify({keys:[jwk]}),{mode:0o600});
const codes=new Map();
const encode=x=>Buffer.from(JSON.stringify(x)).toString('base64url');
const server=http.createServer(async(req,res)=>{
 try {
  const url=new URL(req.url,'http://localhost:9091');
  if(url.pathname==='/health'){res.end('ready');return;}
  const match=/^\/(google|kakao)\/(authorize|token|jwks)$/.exec(url.pathname);
  if(!match){res.writeHead(404).end();return;}
  const [,provider,action]=match;
  if(action==='jwks'){res.setHeader('Content-Type','application/json');res.end(JSON.stringify({keys:[jwk]}));return;}
  if(action==='authorize'){
   const p=url.searchParams;
   const redirect=new URL(p.get('redirect_uri'));
   if(redirect.origin!=='http://localhost:8080'||redirect.pathname!==`/api/auth/${provider}/callback`||p.get('code_challenge_method')!=='S256')throw Error('invalid');
   const code=randomUUID();codes.set(code,{provider,nonce:p.get('nonce'),challenge:p.get('code_challenge'),redirect:redirect.href,client:p.get('client_id'),at:Date.now()});
   redirect.searchParams.set('code',code);redirect.searchParams.set('state',p.get('state'));
   res.writeHead(302,{Location:redirect.href}).end();return;
  }
  if(req.method!=='POST')throw Error('invalid');
  let body='';for await(const chunk of req){body+=chunk;if(body.length>8192)throw Error('size');}
  const p=new URLSearchParams(body), entry=codes.get(p.get('code'));codes.delete(p.get('code'));
  if(!entry||entry.provider!==provider||Date.now()-entry.at>60000||p.get('redirect_uri')!==entry.redirect||p.get('client_id')!==entry.client||createHash('sha256').update(p.get('code_verifier')??'').digest('base64url')!==entry.challenge)throw Error('invalid');
  const now=Math.floor(Date.now()/1000),header=encode({alg:'RS256',kid:'issue108-local',typ:'JWT'}),payload=encode({iss:`http://localhost:9091/${provider}`,sub:`synthetic-${provider}-108`,aud:entry.client,nonce:entry.nonce,iat:now,exp:now+120,name:provider==='google'?'합성 Google 회원':'합성 Kakao 회원'});
  const unsigned=`${header}.${payload}`,token=`${unsigned}.${sign('RSA-SHA256',Buffer.from(unsigned),privateKey).toString('base64url')}`;
  res.setHeader('Content-Type','application/json');res.end(JSON.stringify({id_token:token,token_type:'Bearer'}));
 }catch{res.writeHead(400,{'Content-Type':'application/json'}).end('{"error":"invalid_request"}');}
});
server.listen(9091,'127.0.0.1',()=>process.stdout.write('Loopback synthetic OIDC ready on 9091\n'));
