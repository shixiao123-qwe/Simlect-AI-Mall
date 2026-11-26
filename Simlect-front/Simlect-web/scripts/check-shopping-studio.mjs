import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import ts from 'typescript';
import {parse,compileScript,compileTemplate} from '@vue/compiler-sfc';
const root=new URL('../src/',import.meta.url);
for(const file of ['components/business/AIGuideCard.vue','components/agent/AgentShoppingEvidence.vue','components/agent/AgentProductList.vue','components/agent/AgentChatItem.vue','views/AfterSaleRequestsView.vue','views/agent/AgentSendPanel.vue']){
 const source=readFileSync(new URL(file,root),'utf8');const {descriptor,errors}=parse(source,{filename:file});assert.equal(errors.length,0);
 const script=compileScript(descriptor,{id:file});const template=compileTemplate({source:descriptor.template.content,filename:file,id:file,compilerOptions:{bindingMetadata:script.bindings}});assert.deepEqual(template.errors,[]);
}
function loadTS(file,imports={}){const code=ts.transpileModule(readFileSync(new URL(file,root),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;const context={exports:{},require:name=>{assert.ok(name in imports,`Unexpected dependency ${name}`);return imports[name]}};vm.runInNewContext(code,context);return context.exports;}
const {uniqueProducts,afterSaleProgress}=loadTS('utils/shoppingPresentation.ts');
assert.equal(uniqueProducts([{productId:'a'},{productId:'a'},{productId:'b'},{productId:null}]).length,2);
assert.equal(uniqueProducts([{productId:'a'}]).length,1,'Never pad the catalog');
assert.equal(uniqueProducts([{productId:'a'},{productId:'b'}],1).length,1);
assert.equal(afterSaleProgress('WAIT_RETURN','return_refund').active,2);
assert.equal(afterSaleProgress('WAIT_INSPECTION','return_refund').active,3);
assert.equal(afterSaleProgress('COMPLETED','return_refund').active,5);
assert.equal(afterSaleProgress('COMPLETED','refund').steps.length,3);
assert.equal(afterSaleProgress('REJECTED','return_refund').error,true);
assert.equal(afterSaleProgress('REFUND_FAILED','refund').error,true);
assert.equal(afterSaleProgress('UNKNOWN','refund').active,0);
const {usePcAgentPanelStore}=loadTS('stores/pcAgentPanel.ts',{'pinia':{defineStore:(_name,create)=>create},'vue':{ref:value=>({value})}});
const store=usePcAgentPanelStore();store.open({presetMessage:'预算300元厨具'});assert.equal(store.visible.value,true);assert.equal(store.consumePresetMessage(),'预算300元厨具');assert.equal(store.consumePresetMessage(),'');store.open({presetMessage:'draft'});store.close();assert.equal(store.presetMessage.value,'');
store.open({presetMessage:'another draft'});store.toggle();assert.equal(store.presetMessage.value,'');
const openAgent=readFileSync(new URL('composables/useOpenAgent.ts',root),'utf8');assert.ok(openAgent.includes('presetMessage: options?.presetMessage'));
console.log('Shopping studio: 6 SFC compilations; real catalog, after-sale steps and single-consume draft checks passed');
