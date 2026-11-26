import fs from 'node:fs';
import assert from 'node:assert/strict';
import {parse,compileScript,compileTemplate} from '@vue/compiler-sfc';
const filename='src/views/account/Account.vue';
const {descriptor,errors}=parse(fs.readFileSync(filename,'utf8'));assert.equal(errors.length,0);
const compiled=compileScript(descriptor,{id:'captcha-retry'});
const decl=compiled.scriptSetupAst.filter(n=>n.type==='VariableDeclaration').flatMap(n=>n.declarations).find(n=>n.id.name==='changeCheckCode');
assert.ok(decl);const code=descriptor.scriptSetup.content.slice(decl.init.start,decl.init.end);
const bind=(proxy,loading,info)=>new Function('proxy','captchaLoading','checkCodeInfo','return '+code)(proxy,loading,info);
for(const scenario of ['success','null','throw','malformed','busy']){
 let calls=0;const loading={value:scenario==='busy'},info={value:{checkCode:'old',checkCodeKey:'old-key'}};
 const valid={data:{checkCode:'data:image/png;base64,test',checkCodeKey:'new-key'}};
 const run=bind({Api:{checkCode:'/account/checkCode'},Request:async()=>{calls++;if(scenario==='throw')throw new Error('offline');return scenario==='null'?null:scenario==='malformed'?{data:{checkCode:'bad'}}:valid}},loading,info);
 await run();assert.equal(calls,scenario==='busy'?0:1);
 assert.equal(loading.value,scenario==='busy');
 if(scenario==='success')assert.equal(info.value.checkCodeKey,'new-key');
 else if(scenario!=='busy')assert.deepEqual(info.value,{});
}
{
 let resolve,calls=0;const loading={value:false},info={value:{}};
 const run=bind({Api:{checkCode:'/account/checkCode'},Request:()=>{calls++;return new Promise(r=>resolve=r)}},loading,info);
 const first=run();assert.equal(loading.value,true);await run();assert.equal(calls,1);
 resolve({data:{checkCode:'image',checkCodeKey:'key'}});await first;assert.equal(loading.value,false);
}
const template=compileTemplate({source:descriptor.template.content,filename,id:'captcha-retry'});assert.equal(template.errors.length,0);
assert.ok(descriptor.template.content.includes(':disabled="captchaLoading"'));
console.log('Admin captcha: 6 behavior checks and SFC compilation passed');
