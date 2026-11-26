<template>
  <section class="shopping-evidence">
    <div class="evidence-heading"><span>{{ meta.mode==='compare' ? '选购对比' : '本次选购' }}</span><span class="verified-badge">SKU 价格与库存已核验</span></div>
    <div v-if="meta.conditions?.length" class="condition-tags" aria-label="当前筛选条件"><span v-for="condition in meta.conditions" :key="condition">{{ condition }}</span></div>
    <p class="conclusion">{{ meta.conclusion }}</p>
    <p v-if="meta.unavailableCount" class="missing">所选商品中有 {{ meta.unavailableCount }} 件不可比较，可能已下架或无库存。</p>
    <div v-if="meta.mode==='compare'" class="compare-scroll"><table><caption>仅展示已录入并核验的数据</caption><thead><tr><th>比较项</th><th v-for="(item,index) in list" :key="item.productId">{{ index+1 }} · {{ item.productName }}</th></tr></thead><tbody><tr><th>价格</th><td v-for="item in list" :key="item.productId">¥{{ item.verified?.price ?? '暂无数据' }}</td></tr><tr><th>可售库存</th><td v-for="item in list" :key="item.productId">{{ item.verified?.stock ?? '暂无数据' }}</td></tr><tr v-for="field in fields" :key="field"><th>{{ field }}</th><td v-for="item in list" :key="item.productId">{{ item.verified?.attributes?.[field] || '暂无数据' }}</td></tr><tr><th>实测表现与不足</th><td v-for="item in list" :key="item.productId">暂无数据，不作推测</td></tr></tbody></table></div>
    <p class="snapshot-note">价格与库存为查询时快照，以下单时再次校验为准。</p>
  </section>
</template>
<script setup lang="ts">
import {computed} from 'vue';
const props=defineProps<{list:Record<string,any>[];meta:Record<string,any>}>();
const fields=computed(()=>[...new Set(props.list.flatMap(item=>Object.keys(item.verified?.attributes || {})))].slice(0,12));
</script>
<style scoped>.shopping-evidence{margin-bottom:14px}.evidence-heading{display:flex;justify-content:space-between;align-items:center;gap:12px;font-size:15px;font-weight:700;color:#2d4937}.verified-badge{font-size:10px;font-weight:400;color:#73876e}.condition-tags{display:flex;flex-wrap:wrap;gap:6px;margin:12px 0}.condition-tags span{font-size:11px;padding:5px 9px;background:#edf3e8;color:#4d6846;border-radius:20px}.conclusion{font-size:14px;line-height:1.8;color:#46533f}.snapshot-note{font-size:11px;color:#89907f}.compare-scroll{overflow-x:auto;border:1px solid #e3e8db;border-radius:10px}table{border-collapse:collapse;font-size:12px;min-width:360px;width:100%}caption{padding:8px;background:#f5f7f0;font-size:10px;color:#78806d}th,td{text-align:left;padding:10px;border-bottom:1px solid #edf0e7;min-width:100px;max-width:180px;overflow-wrap:anywhere;vertical-align:top}thead th{background:#edf3e8;color:#4a6340}tbody th{background:#f9faf5;color:#718067;font-weight:500}.missing{font-size:12px;color:#9a6c3c}</style>
