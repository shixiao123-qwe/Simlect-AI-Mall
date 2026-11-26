<template>
  <section class="requests">
    <header class="requests-head"><span class="eyebrow">SERVICE DESK</span><h2>售后服务台</h2><p>每一次处理，都有清晰的进度。</p></header>
    <p>这里展示 AI 或网页提交的售后申请。批准不等于已退款；退货需人工验货。</p>
    <el-button @click="load" :loading="loading">刷新进度</el-button>
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" style="margin-top:16px" />
    <el-empty v-if="!loading && !loadError && !rows.length" description="暂无售后申请" />
    <article v-for="row in rows" :key="row.requestId" class="request">
      <h3>{{ row.productName || '订单商品' }} · {{ types[row.type] || row.type }}</h3>
      <p>申请编号：<code>{{ row.requestId }}</code></p><p>订单项：<code>{{ row.orderItemId }}</code></p>
      <p><strong>{{ phases[row.phase] || row.status }}</strong></p>
      <ol class="progress-track" :class="{failed:afterSaleProgress(row.phase,row.type).error}" aria-label="售后处理步骤"><li v-for="(step,index) in afterSaleProgress(row.phase,row.type).steps" :key="step" :class="{done:index<afterSaleProgress(row.phase,row.type).active,current:index===afterSaleProgress(row.phase,row.type).active}"><span>{{ index+1 }}</span>{{ step }}</li></ol>
      <p class="next-action" role="status">{{ afterSaleProgress(row.phase,row.type).tip }}</p><p>申请原因：{{ row.reason }}</p>
      <p v-if="row.reviewNote">审核意见：{{ row.reviewNote }}</p>
      <template v-if="row.returnAddress"><p>退货地址：{{ row.returnAddress }}</p><p class="warning" v-if="row.returnAddress.includes('演示')">此为演示地址，请勿寄送真实包裹；可用测试单号验证流程。</p></template>
      <p v-if="row.returnTracking">已填物流：{{ row.returnCarrier }} / {{ row.returnTracking }}（待人工核验，不代表物流商已验真）</p>
      <p v-if="row.inspectionNote">验货意见：{{ row.inspectionNote }}</p>
      <p v-if="row.status === 'COMPLETED'">{{ row.simulated ? '本地模拟退款完成，未发生真实资金退回' : '支付渠道退款已处理，请核对到账情况' }}；金额 ¥{{ row.refundAmount }}；退款单号 {{ row.refundReference }}</p>
      <el-button v-if="row.phase === 'WAIT_RETURN'" type="primary" @click="openParcel(row)">填写退货物流</el-button>
      <el-button @click="showDetail(row.requestId)">查看操作记录</el-button>
    </article>
    <el-dialog v-model="parcelVisible" title="提交退货物流" width="min(550px,95vw)">
      <p>请先核对审核通过的退货地址，再提交物流。演示地址不得实际寄件。</p>
      <el-input v-model="parcel.carrier" maxlength="50" placeholder="快递公司，如顺丰/演示快递" />
      <el-input v-model="parcel.tracking" maxlength="64" placeholder="物流单号，测试可用DEMO123456" class="input" />
      <template #footer><el-button @click="parcelVisible=false">取消</el-button><el-button type="primary" :loading="submitting" @click="submitParcel">确认提交退货单号</el-button></template>
    </el-dialog>
    <el-dialog v-model="detailVisible" title="售后操作记录" width="min(800px,95vw)">
      <el-table :data="logs"><el-table-column prop="createdAt" label="时间" min-width="170"/><el-table-column prop="fromStatus" label="原阶段"/><el-table-column prop="toStatus" label="新阶段"/><el-table-column prop="remark" label="记录" min-width="220"/></el-table>
    </el-dialog>
  </section>
</template>
<script setup lang="ts">
import {ref,onMounted} from 'vue';
import {afterSaleProgress} from '@/utils/shoppingPresentation';
import {ElMessage,ElMessageBox} from 'element-plus';
import {afterSaleApi} from '@/api/modules';
const rows=ref<Record<string,any>[]>([]),logs=ref<Record<string,any>[]>([]),loading=ref(false),submitting=ref(false);
const loadError=ref('');
const parcelVisible=ref(false),detailVisible=ref(false),parcel=ref({requestId:'',carrier:'',tracking:''});
const phases:Record<string,string>={PENDING_REVIEW:'待人工审核',WAIT_RETURN:'审核通过，等待寄回',WAIT_INSPECTION:'已提交物流，等待仓库验货',READY_REFUND:'等待管理员执行退款',REFUNDING:'退款处理中',REFUND_FAILED:'退款未完成，请等待人工核实',REJECTED:'已驳回',COMPLETED:'退款履约完成'};
const types:Record<string,string>={refund:'退款',return_refund:'退货退款',exchange:'换货'};
async function load(){loading.value=true;loadError.value='';try{rows.value=await afterSaleApi.progress() || []}catch{loadError.value='售后服务暂不可用，数据可能未更新，请点击刷新重试';ElMessage.error('加载售后申请失败，请重试')}finally{loading.value=false}}
function openParcel(row:Record<string,any>){parcel.value={requestId:row.requestId,carrier:'',tracking:''};parcelVisible.value=true}
async function submitParcel(){
 if(!parcel.value.carrier.trim() || !/^[A-Za-z0-9-]{6,64}$/.test(parcel.value.tracking))return ElMessage.warning('请填写快递公司及6至64位有效物流单号');
 try{await ElMessageBox.confirm('确认物流单号和申请匹配？提交后将等待仓库人工核验。','提交退货信息');}catch{return}
 if(submitting.value)return;submitting.value=true;
 try{await afterSaleApi.returnParcel({...parcel.value,carrier:parcel.value.carrier.trim()});parcelVisible.value=false;ElMessage.success('退货信息已提交，等待验货');await load()}catch{ElMessage.error('提交失败，请刷新核对申请状态')}finally{submitting.value=false}
}
async function showDetail(id:string){try{const detail=await afterSaleApi.detail(id);logs.value=detail.logs || [];detailVisible.value=true}catch{ElMessage.error('无法加载记录')}}
onMounted(load);
</script>
<style scoped>.requests{max-width:1100px;margin:auto;padding:24px}.request{padding:20px;margin:20px 0;background:white;border:1px solid #ddd;border-radius:12px}.request code{overflow-wrap:anywhere}.warning{color:#b45309}.input{margin-top:14px}</style>

<style scoped>.requests-head{padding:28px 0 16px}.eyebrow{font-size:12px;letter-spacing:.16em;color:#66816a}.requests-head h2{font-size:30px;margin:10px 0;color:#223e30}.requests-head p{color:#7a857a}.progress-track{padding:20px 0;list-style:none;display:flex;gap:12px;flex-wrap:wrap}.progress-track li{display:flex;align-items:center;gap:8px;color:#8b928a;font-size:13px}.progress-track li:after{content:'→';margin-left:8px;color:#c8d0c5}.progress-track li:last-child:after{content:''}.progress-track li span{display:grid;place-items:center;border:1px solid #dce3d8;border-radius:50%;width:24px;height:24px}.progress-track li.done{color:#487551}.progress-track li.done span{background:#e7efe3;border-color:#d6e3ce}.progress-track li.current{font-weight:700;color:#244d3b}.progress-track li.current span{background:#244d3b;color:white}.progress-track.failed li{color:#9b5c39}.next-action{padding:14px 18px;background:#f1f5ed;border-left:3px solid #6e9470;color:#3b5540;border-radius:6px}.request h3{font-size:19px}.request{box-shadow:0 8px 24px #25422b04}</style>
