import fs from 'node:fs';
import assert from 'node:assert/strict';
import ts from 'typescript';
import {parse, compileScript, compileTemplate} from '@vue/compiler-sfc';
const source=fs.readFileSync('src/composables/usePaymentPage.ts','utf8');
const tree=ts.createSourceFile('payment.ts',source,ts.ScriptTarget.Latest,true);
let initializer;
function visit(node) {
  if(ts.isVariableDeclaration(node) && node.name.getText(tree)==='startPay') initializer=node.initializer.getText(tree);
  ts.forEachChild(node,visit);
}
visit(tree);assert.ok(initializer);
const compiled=ts.transpileModule('const startPay='+initializer.replaceAll('import.meta.env','testEnv'),{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText;
for(const [name,env,data,expected] of [
 ['local placeholder',{DEV:true,VITE_PAY_LOCAL_MOCK_ENABLED:'true'},{payOrderId:'p',amount:199},'demo'],
 ['ordinary empty response',{DEV:true},{payOrderId:'p'},'error'],
 ['production never assumes demo',{DEV:false,VITE_PAY_LOCAL_MOCK_ENABLED:'true'},{payOrderId:'p'},'error'],
 ['malformed demo response',{DEV:true,VITE_PAY_LOCAL_MOCK_ENABLED:'true'},{},'error'],
 ['real HTML still launches',{DEV:true,VITE_PAY_LOCAL_MOCK_ENABLED:'true'},{payOrderId:'p',payInfo:'<form></form>',amount:199},'launch'],
]) {
 const refs=Object.fromEntries(['localDemo','launching','isProcessing','loadError','payHtml','payAmount','orderInfo'].map(k=>[k,{value:k==='loadError'?'':false}]));
 let launched=false;
 const deps={...refs,testEnv:env,payLookupId:()=> 'p',redirectIfNotPayable:async()=>false,loadOrder:async()=>false,isAlipayReturn:()=>false,
 orderApi:{getPayInfo:async()=>data},resolvePayAmount:x=>x,launchAlipay:()=>{launched=true;return true;},isMobile:false};
 const run=new Function(...Object.keys(deps),compiled+';return startPay;')(...Object.values(deps));
 await run();
 assert.equal(Boolean(refs.localDemo.value),expected==='demo',name);
 assert.equal(Boolean(refs.loadError.value),expected==='error',name);
 assert.equal(launched,expected==='launch',name);
 assert.equal(refs.launching.value,false,name);
}
let confirmInitializer;
function findConfirm(node) {
  if(ts.isVariableDeclaration(node) && node.name.getText(tree)==='confirmDemoPayment') confirmInitializer=node.initializer.getText(tree);
  ts.forEachChild(node,findConfirm);
}
findConfirm(tree);assert.ok(confirmInitializer);
const confirmCode=ts.transpileModule('const confirmDemoPayment='+confirmInitializer,{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText;
for(const scenario of ['success','request fails','readback fails','bad response','disabled','double click']) {
 const refs={localDemo:{value:scenario!=='disabled'},demoPayLoading:{value:scenario==='double click'},demoPaid:{value:false}};
 let calls=0,success=0,errors=0;
 const deps={...refs,payOrderId:()=> 'server-id',loadOrder:async()=>scenario!=='readback fails',
 toast:{success:()=>success++,error:()=>errors++},orderApi:{confirmLocalDemoPayment:async id=>{
  calls++;assert.equal(id,'server-id');if(scenario==='request fails') throw {info:'closed'};
  return {simulated:scenario!=='bad response'};
 }}};
 const run=new Function(...Object.keys(deps),confirmCode+';return confirmDemoPayment;')(...Object.values(deps));
 await run();
 assert.equal(calls,scenario==='disabled'||scenario==='double click'?0:1,scenario);
 assert.equal(success,scenario==='success'?1:0,scenario);
 assert.equal(refs.demoPaid.value,scenario==='success',scenario);
 if(['request fails','readback fails','bad response'].includes(scenario)) assert.equal(errors,1,scenario);
}
for(const filename of ['src/views/PaymentView.vue','src/views/pc/PcPaymentView.vue']) {
 const {descriptor,errors}=parse(fs.readFileSync(filename,'utf8'));assert.equal(errors.length,0);
 compileScript(descriptor,{id:filename});
 const result=compileTemplate({source:descriptor.template.content,filename,id:filename});assert.equal(result.errors.length,0);
}
console.log('Local payment: 11 behavior checks and both payment SFC compilations passed');
