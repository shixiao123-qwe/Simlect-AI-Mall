import fs from 'node:fs';
import ts from 'typescript';
import assert from 'node:assert/strict';
import {parse,compileScript,compileTemplate} from '@vue/compiler-sfc';
function functionCode(source,name){
 const tree=ts.createSourceFile('source.ts',source,ts.ScriptTarget.Latest,true);let found;
 function visit(node){if(ts.isFunctionDeclaration(node)&&node.name?.text===name)found=node.getText(tree);if(ts.isVariableDeclaration(node)&&node.name.getText(tree)===name)found='const '+name+'='+node.initializer.getText(tree);ts.forEachChild(node,visit)}visit(tree);
 assert.ok(found,name);return ts.transpileModule(found.replaceAll('import.meta.env','testEnv'),{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText;
}
function bind(source,name,deps){return new Function(...Object.keys(deps),functionCode(source,name)+';return '+name)(...Object.values(deps))}
const files=['src/views/AfterSaleRequestsView.vue','../Simlect-admin/src/views/order/AfterSaleRequests.vue','../Simlect-admin/src/views/order/Delivery.vue','src/views/AfterSaleView.vue'];
const scripts={};
for(const file of files){const {descriptor,errors}=parse(fs.readFileSync(file,'utf8'));assert.equal(errors.length,0);compileScript(descriptor,{id:file});const result=compileTemplate({source:descriptor.template.content,filename:file,id:file});assert.equal(result.errors.length,0);scripts[file]=descriptor.scriptSetup.content}
let checks=0;
const helper=fs.readFileSync('../Simlect-admin/src/utils/sensitiveConfirm.js','utf8');
for(const prod of [false,true]){
 let call;
 const exchange=bind(helper,'exchangeConfirmToken',{testEnv:{PROD:prod},axios:{post:async(...args)=>{call=args;return {data:{status:'success',data:'nonce'}}}}});
 assert.equal(await exchange('test-password','/admin/order/afterSale/refund'),'nonce');
 assert.equal(call[0],prod?'/admin-api/account/confirmPassword':'/api/account/confirmPassword');
 assert.equal(call[1].get('targetPath'),'/admin/order/afterSale/refund');assert.ok(call[2].withCredentials);checks++;
}
const admin=scripts[files[1]];
for(const success of [true,false]){
 let request,confirmation,work;let messages=0;
 const call=bind(admin,'sensitive',{busy:{value:''},simulated:{value:true},ConfirmSensitive:args=>{confirmation=args;work=args.okfun('nonce')},Request:async args=>{request=args;return success?{data:{}}:null},ElMessage:{success:()=>messages++},load:async()=>{}});
 call('refund',{requestId:'a'.repeat(32)},'confirm');await work;
 assert.equal(confirmation.targetPath,'/admin/order/afterSale/refund');assert.equal(request.sensitiveConfirmToken,'nonce');
 assert.equal(request.url,'/order/afterSale/refund');assert.equal(messages,success?1:0);checks++;
}
{
 let args;
 const run=bind(admin,'submitInspection',{inspection:{value:{requestId:'a'.repeat(32),accepted:false,restockable:true,note:'wrong goods'}},inspectVisible:{value:true},sensitive:(...a)=>args=a,ElMessage:{warning:()=>{throw new Error('unexpected')}}});
 run();assert.equal(args[1].restockable,false);checks++;
}
const user=scripts[files[0]];
for(const scenario of ['success','cancel','bad tracking','busy','server failure']){
 let sent=0,success=0;
 const parcel={value:{requestId:'a'.repeat(32),carrier:'demo',tracking:scenario==='bad tracking'?'bad':'DEMO123456'}};
 const run=bind(user,'submitParcel',{parcel,submitting:{value:scenario==='busy'},parcelVisible:{value:true},
 ElMessage:{warning:()=>{},success:()=>success++,error:()=>{}},ElMessageBox:{confirm:async()=>{if(scenario==='cancel')throw new Error('cancel')}},
 afterSaleApi:{returnParcel:async()=>{sent++;if(scenario==='server failure')throw new Error('fail')}},load:async()=>{}});
 await run();assert.equal(sent,['success','server failure'].includes(scenario)?1:0);assert.equal(success,scenario==='success'?1:0);checks++;
}
const delivery=scripts[files[2]];
for(const scenario of ['valid','cancel','invalid']){
 let validation,request,count=0;
 const deps={formDataRef:{value:{validate:callback=>{validation=callback(scenario!=='invalid')}}},formData:{value:{orderId:'o',recordList:['omit']}},
 promptAdminConfirmPassword:async(message,target)=>{assert.equal(target,'/admin/order/delivery');if(scenario==='cancel')throw new Error('cancel');return 'nonce'},
 proxy:{Api:{delivery:'/order/delivery'},Request:async args=>{request=args;count++;return {data:{}}}},dialogConfig:{value:{show:true}},emit:()=>{}};
 const run=bind(delivery,'submitForm',deps);run();await validation;
 assert.equal(count,scenario==='valid'?1:0);if(request){assert.equal(request.sensitiveConfirmToken,'nonce');assert.equal(request.params.recordList,undefined)}checks++;
}
{
 let failed=true,requests=0;const deps={loading:{value:false},loadError:{value:''},rows:{value:[{requestId:'cached'}]},ElMessage:{error:()=>{}},afterSaleApi:{progress:async()=>{requests++;if(failed)throw new Error('offline');return [{requestId:'new'}]}}};
 const run=bind(user,'load',deps);await run();assert.ok(deps.loadError.value);assert.equal(deps.rows.value[0].requestId,'cached');assert.equal(deps.loading.value,false);checks++;
 failed=false;await run();assert.equal(deps.loadError.value,'');assert.equal(deps.rows.value[0].requestId,'new');assert.equal(requests,2);checks++;
}
{
 const old=scripts[files[3]];let failing=true,requests=0;
 const deps={loadError:{value:''},loadingMore:{value:false},finished:{value:false},pageNo:{value:0},pageTotal:{value:1},list:{value:[]},loading:{value:false},tab:{value:'all'},
 fetchPage:async()=>{requests++;if(failing)throw new Error('offline');return {list:[{orderId:'o'}],pageNo:1,pageTotal:1}},mergeOrders:x=>x,nextTick:callback=>callback(),setupObserver:()=>{}};
 const load=bind(old,'loadMore',deps);await load();assert.ok(deps.loadError.value);await load();assert.equal(requests,1);checks++;
 failing=false;let task;
 const reset=bind(old,'onTabChange',{...deps,pageCache:{clear:()=>{}},usesWindowScroll:()=>false,scrollRoot:{value:null},loadMore:()=>task=load()});reset();await task;
 assert.equal(requests,2);assert.equal(deps.loadError.value,'');assert.equal(deps.list.value.length,1);checks++;
}
console.log(`After-sale fulfillment: ${checks} behavior checks and 4 SFC compilations passed`);
