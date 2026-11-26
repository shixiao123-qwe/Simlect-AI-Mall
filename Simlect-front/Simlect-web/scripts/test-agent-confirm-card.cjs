const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const {createRequire} = require('node:module');
const root = path.resolve(__dirname, '../../..');
const local = createRequire(path.join(root, 'Simlect-front/Simlect-web/package.json'));
const {parse, compileScript} = local('@vue/compiler-sfc');
const ts = local('typescript');
const {createSSRApp, h} = local('vue');
const {renderToString} = local('@vue/server-renderer');
const source = fs.readFileSync(path.join(root, 'Simlect-front/Simlect-web/src/components/agent/AgentConfirmCard.vue'), 'utf8');
const {descriptor} = parse(source);
const script = compileScript(descriptor, {id:'t04-card', inlineTemplate:true});
const js = ts.transpileModule(script.content, {compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
const moduleResult = {exports:{}};
const stub = {render:()=>null};
const controlledRequire = name => {
  if(name==='@/api/modules')return {agentApi:{}};
  if(name==='@/utils/toast')return {toast:{}};
  if(name==='@/components/common/ProductImage.vue')return {default:stub};
  if(name==='@element-plus/icons-vue')return {Ticket:stub};
  return local(name);
};
new Function('require','exports','module',js)(controlledRequire,moduleResult.exports,moduleResult);
const component = moduleResult.exports.default;
(async()=>{
  const cases=[[0,'待确认'],[1,'已确认提交申请'],[2,'已取消'],[3,'已过期'],[4,'正在提交'],[5,'申请失败'],[6,'正在核实结果']];
  for(const [status,label] of cases){
    const app=createSSRApp({render:()=>h(component,{card:{status,actionType:'AFTER_SALE_REQUEST',token:'act_test'}})});
    app.component('el-icon',stub);
    const html=await renderToString(app);
    assert.ok(html.includes(label), `status ${status}: expected ${label}`);
    if([1,2,3,5].includes(status))assert.ok(!html.includes('确认提交</button>'));
    console.log(`PASS status=${status} ${label}`);
  }
  console.log('7 confirmation card state render checks passed (SSR, no browser or external calls)');
})().catch(error=>{console.error(error);process.exitCode=1;});
