<template>
  <section class="after-sale-admin">
    <h2>售后审核与退款履约</h2>
    <el-alert :title="simulated ? '当前为本地模拟资金模式，不会发生真实扣款或退款' : '当前为支付渠道模式，执行退款将调用已配置渠道，请核对金额与验货记录'" :type="simulated ? 'info' : 'warning'" :closable="false" />
    <div class="toolbar">
      <el-select v-model="status" clearable placeholder="全部状态" @change="load"><el-option v-for="(v,k) in statuses" :key="k" :label="v" :value="k" /></el-select>
      <el-button @click="load">刷新</el-button>
      <span>第 {{ page }} 页，每页最多50条</span><el-button :disabled="page === 1" @click="page--; load()">上一页</el-button><el-button :disabled="rows.length < 50" @click="page++; load()">下一页</el-button>
    </div>
    <el-table :data="rows" v-loading="loading" border>
      <el-table-column prop="requestId" label="申请编号" min-width="230" />
      <el-table-column prop="productName" label="商品" min-width="180" />
      <el-table-column label="申请类型" width="110"><template #default="{row}">{{ types[row.type] || row.type }}</template></el-table-column>
      <el-table-column label="状态" width="150"><template #default="{row}">{{ phases[row.phase] || statuses[row.status] }}</template></el-table-column>
      <el-table-column prop="reason" label="原因" min-width="150" />
      <el-table-column label="操作" min-width="320"><template #default="{row}">
        <el-button size="small" @click="showDetail(row)">详情/审计</el-button>
        <el-button v-if="row.status === 'PENDING_REVIEW'" size="small" :disabled="!!busy" @click="review(row,true)">批准</el-button>
        <el-button v-if="['PENDING_REVIEW','APPROVED'].includes(row.status) && !row.refundReference && !['REFUNDING','REFUND_FAILED'].includes(row.phase)" size="small" :disabled="!!busy" @click="review(row,false)">驳回</el-button>
        <el-button v-if="row.phase === 'WAIT_INSPECTION'" size="small" :disabled="!!busy" @click="openInspect(row)">仓库验货</el-button>
        <el-button v-if="row.status === 'APPROVED' && ['READY_REFUND','REFUND_FAILED'].includes(row.phase)" type="danger" size="small" :loading="busy === row.requestId" :disabled="!!busy" @click="executeRefund(row)">{{ simulated ? '执行模拟退款' : '执行渠道退款' }}</el-button>
      </template></el-table-column>
    </el-table>
    <el-dialog v-model="detailVisible" title="售后详情与操作记录" width="min(800px,95vw)">
      <template v-if="detail"><p>订单项：{{ detail.orderItemId }}</p><p>审核意见：{{ detail.reviewNote || '暂无' }}</p><p>退货地址：{{ detail.returnAddress || '无需退货/未批准' }}</p><p>退货物流：{{ detail.returnCarrier || '暂无' }} / {{ detail.returnTracking || '暂无' }}</p><p>验货意见：{{ detail.inspectionNote || '暂无' }}</p><p>可售库存回补：{{ detail.type === 'refund' ? '未发货退款按原流程回补' : detail.restockable ? '验货允许重新入库' : '不回补可售库存，退货需隔离' }}</p><p>退款金额：{{ detail.refundAmount == null ? '尚未确认，请结合申请阶段核实' : '¥' + detail.refundAmount }}；退款单号：{{ detail.refundReference || '暂无' }}</p>
      <el-table :data="detail.logs || []"><el-table-column prop="createdAt" label="时间" min-width="180"/><el-table-column prop="fromStatus" label="原阶段"/><el-table-column prop="toStatus" label="新阶段"/><el-table-column prop="remark" label="记录" min-width="220"/></el-table></template>
    </el-dialog>
    <el-dialog v-model="inspectVisible" title="仓库验货（人工核实，不代表物流商已验真）" width="min(600px,95vw)">
      <p>先核实包裹、商品与订单项，再记录结果。</p>
      <el-switch v-model="inspection.accepted" active-text="接受退货退款" inactive-text="拒绝验货" />
      <p><el-checkbox v-model="inspection.restockable" :disabled="!inspection.accepted">商品适合重新销售，退款后可回补库存（损坏品请勿勾选）</el-checkbox></p>
      <el-input v-model="inspection.note" type="textarea" maxlength="300" show-word-limit placeholder="填写验货证据和原因" />
      <template #footer><el-button @click="inspectVisible=false">取消</el-button><el-button type="primary" @click="submitInspection">记录验货结果</el-button></template>
    </el-dialog>
  </section>
</template>
<script setup>
import {ref,onMounted} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import Request from '@/utils/Request'
import {ConfirmSensitive} from '@/utils/sensitiveConfirm'
const rows=ref([]),loading=ref(false),status=ref(''),page=ref(1),simulated=ref(true),busy=ref('')
const detail=ref(null),detailVisible=ref(false),inspectVisible=ref(false)
const inspection=ref({requestId:'',accepted:true,restockable:false,note:''})
const statuses={PENDING_REVIEW:'待审核',APPROVED:'已批准',REJECTED:'已驳回',COMPLETED:'退款履约完成'}
const phases={PENDING_REVIEW:'待审核',WAIT_RETURN:'待用户寄回',WAIT_INSPECTION:'待仓库验货',READY_REFUND:'可执行退款',REFUNDING:'退款处理中',REFUND_FAILED:'退款未完成/待核实',REJECTED:'已驳回',COMPLETED:'退款履约完成'}
const types={refund:'退款',return_refund:'退货退款',exchange:'换货（暂未接出库）'}
async function load(){loading.value=true;try{const r=await Request({url:'/order/afterSale/list',dataType:'json',params:{status:status.value || null,pageNo:page.value}});if(r){rows.value=r.data.list;simulated.value=r.data.simulated}}finally{loading.value=false}}
async function showDetail(row){const r=await Request({url:'/order/afterSale/detail',dataType:'json',params:{requestId:row.requestId}});if(r){detail.value=r.data;detailVisible.value=true}}
function sensitive(action,params,message){
 const url='/order/afterSale/'+action
 ConfirmSensitive({message,url,targetPath:'/admin'+url,okfun:async token=>{if(busy.value)return;busy.value=params.requestId;try{const r=await Request({url,params,dataType:'json',sensitiveConfirmToken:token,timeout:60000});if(r){ElMessage.success(action==='refund'?(simulated.value?'本地模拟退款完成，未发生真实资金退回':'退款已处理，请核对渠道及库存补偿记录'):'操作已记录');await load()}}finally{busy.value=''}}})
}
async function review(row,approved){try{const {value}=await ElMessageBox.prompt(approved?'填写审核依据（不等于已退款）':'填写驳回原因','审核售后',{inputType:'textarea',inputValidator:v=>!!v?.trim() && v.length<=300 || '请填写1至300字原因'});sensitive('review',{requestId:row.requestId,approved,note:value.trim()},approved?'确认批准该售后申请？退货退款仍需收货验货后才能退款。':'确认驳回该申请？')}catch{}}
function openInspect(row){inspection.value={requestId:row.requestId,accepted:true,restockable:false,note:''};inspectVisible.value=true}
function submitInspection(){if(!inspection.value.note.trim())return ElMessage.warning('请填写验货意见');const params={...inspection.value,note:inspection.value.note.trim(),restockable:inspection.value.accepted && inspection.value.restockable};inspectVisible.value=false;sensitive('inspect',params,'确认验货结果？损坏商品不会回补可售库存。')}
function executeRefund(row){sensitive('refund',{requestId:row.requestId},simulated.value?'确认执行本地模拟退款？订单与库存会按业务流程更新，不发生真实资金退回。':'确认调用支付渠道退款？请先核实用户、订单项和验货结果。')}
onMounted(load)
</script>
<style scoped>.after-sale-admin{padding:20px}.toolbar{display:flex;gap:12px;align-items:center;margin:18px 0;flex-wrap:wrap}.toolbar .el-select{width:180px}</style>
