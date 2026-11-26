<template>
  <section class="shopping-studio" aria-labelledby="studio-title">
    <div class="studio-heading">
      <div class="studio-copy">
        <span class="studio-kicker"><span class="kicker-dot" /> AI 帮你选 · 按需发现好物</span>
        <h1 id="studio-title">选到喜欢的，<span>也选得明白。</span></h1>
        <p>告诉我预算和需求，一起比较价格、参数与库存。</p>
        <div class="studio-links"><RouterLink to="/search">逛逛全部商品 <span>↗</span></RouterLink><RouterLink to="/after-sale-requests">我的售后进度 →</RouterLink></div>
      </div>
      <div v-if="featuredProducts.length" class="studio-products" aria-label="发现商品">
        <RouterLink v-for="product in featuredProducts.slice(0,3)" :key="product.productId" :to="`/product/${product.productId}`" class="studio-product">
          <ProductImage :product="product" fit="contain" width="100%" height="100%" />
          <span>{{ product.productName }}</span>
        </RouterLink>
      </div>
      <div v-else class="studio-aside"><span>YOUR SHOPPING COMPANION</span><strong>少一点纠结<br>多一点合适</strong></div>
    </div>
    <form class="studio-form" @submit.prevent="ask">
      <div class="form-title"><span class="ai-badge">AI</span><span>帮我选一选<small>从预算开始，找到适合你的商品</small></span></div>
      <label><span>想买什么</span><input v-model.trim="category" maxlength="80" placeholder="例如：厨具、蓝牙耳机" required></label>
      <label class="budget-label"><span>预算上限</span><span class="budget-field"><span>¥</span><input v-model="budget" type="number" min="1" max="999999" step="1" required></span></label>
      <button class="studio-submit" type="submit">开始 AI 选购 <span>↗</span></button>
    </form>
    <div class="studio-bottom"><div class="quick-categories"><span>试试：</span><button v-for="word in ['厨具','办公用品','蓝牙耳机']" :key="word" type="button" @click="category=word">{{ word }} ↗</button></div><span>需求先填入聊天框，确认后发送</span></div>
  </section>
</template>
<script setup lang="ts">
import {ref} from 'vue';
import {RouterLink} from 'vue-router';
import ProductImage from '@/components/common/ProductImage.vue';
import {useOpenAgent} from '@/composables/useOpenAgent';
withDefaults(defineProps<{featuredProducts?: Record<string, any>[]}>(),{featuredProducts:()=>[]});
const category=ref('厨具'),budget=ref('300');
const {openAgent}=useOpenAgent();
function ask(){const amount=Number(budget.value);if(!category.value || !Number.isFinite(amount) || amount<1 || amount>999999)return;openAgent({presetMessage:`重新选购：推荐${amount}元以内的${category.value}`});}
</script>
<style scoped>
.shopping-studio{box-sizing:border-box;padding:30px 32px 14px;background:linear-gradient(115deg,#fff7ed,#f9f4ef 65%,#f1ede8);border:1px solid #eee3d7;border-radius:18px;color:#292622;margin:0 0 22px;overflow:hidden}
.studio-heading{display:flex;justify-content:space-between;align-items:center;gap:24px;margin-bottom:24px}.studio-copy{flex:1;min-width:0}.studio-kicker{display:inline-flex;align-items:center;gap:7px;color:#996039;font-size:12px;font-weight:600;letter-spacing:1px}.kicker-dot{height:6px;width:6px;border-radius:50%;background:#d76b34}.studio-copy h1{font-size:clamp(26px,2.4vw,36px);font-weight:750;line-height:1.4;letter-spacing:-1px;margin:12px 0 8px}.studio-copy h1 span{color:#b65c30}.studio-copy p{font-size:13px;line-height:1.8;color:#817568;margin:0}.studio-links{display:flex;align-items:center;gap:22px;margin-top:18px}.studio-links a{font-size:12px;text-decoration:none;color:#83786c}.studio-links a:first-child{color:#46382a;font-weight:600}.studio-links a span{margin-left:8px}
.studio-products{display:flex;gap:12px;flex:0 1 430px}.studio-product{width:calc((100% - 24px)/3);height:122px;box-sizing:border-box;background:#ffffffc9;border:1px solid #fff;border-radius:14px;padding:12px 10px 24px;text-decoration:none;color:#66584a;position:relative;transition:transform .2s}.studio-product:hover{transform:translateY(-4px)}.studio-product>span{position:absolute;bottom:8px;left:10px;right:10px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:10px}.studio-aside{text-align:right;color:#9e8c78;min-width:180px}.studio-aside>span{font-size:9px;letter-spacing:2px}.studio-aside strong{display:block;font-size:24px;line-height:1.5;font-weight:500;margin-top:10px}
.studio-form{display:grid;grid-template-columns:1.2fr 1fr .7fr auto;gap:18px;align-items:center;padding:16px 18px;background:#fff;border:1px solid #f0e7df;border-radius:12px;box-shadow:0 6px 20px #76512b05}.form-title{display:flex;align-items:center;gap:10px;font-size:14px;font-weight:600;white-space:nowrap}.form-title small{display:block;font-size:10px;font-weight:400;color:#a09890;margin-top:5px}.ai-badge{display:grid;place-items:center;width:34px;height:34px;border-radius:10px;color:#be632e;background:#fff0df;font-size:13px}.studio-form label{display:flex;flex-direction:column;gap:5px;font-size:10px;color:#8a8177}.studio-form input{width:100%;min-width:0;box-sizing:border-box;border:0;border-bottom:1px solid #e9e3dc;background:transparent;padding:5px 0 7px;color:#342d25;font-size:14px;outline:none}.studio-form input:focus{border-color:#d77b45}.budget-field{display:flex;gap:7px;align-items:center;color:#a98260;font-size:13px}.studio-submit{border:0;border-radius:8px;background:#c46a37;color:#fff;padding:13px 20px;font-size:13px;font-weight:600;cursor:pointer;white-space:nowrap}.studio-submit:hover{background:#ad582b}.studio-submit span{margin-left:12px}.studio-bottom{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:10px;font-size:10px;color:#a19385}.quick-categories{display:flex;gap:12px;align-items:center}.quick-categories button{border:0;background:none;color:#8a7865;font-size:10px;padding:4px 0;cursor:pointer}.quick-categories button:hover{color:#b65c30}
@media(max-width:900px){.shopping-studio{padding:24px}.studio-products{flex-basis:300px}.studio-product{height:110px}.form-title small{display:none}.studio-form{gap:12px;grid-template-columns:auto 1fr .65fr auto}.studio-copy h1 span{display:block}}
@media(max-width:760px){.shopping-studio{margin:12px;padding:20px 16px 12px;border-radius:14px}.studio-heading{margin-bottom:18px}.studio-products,.studio-aside{display:none}.studio-copy h1{font-size:26px}.studio-copy h1 span{display:inline}.studio-copy p{font-size:12px}.studio-links{margin-top:12px}.studio-form{grid-template-columns:1fr .7fr;padding:14px;gap:14px}.form-title{grid-column:1/-1}.studio-submit{grid-column:1/-1}.studio-bottom{align-items:flex-start}.studio-bottom>span{max-width:110px;text-align:right;font-size:9px}}
</style>