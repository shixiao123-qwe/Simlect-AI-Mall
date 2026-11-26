<!-- [zh] SFC 区块开始（单文件组件 template/script/style 三段） -->
<template>
  <!-- [zh] 开始标签 `<div>` -->
  <div class="agent-products">
    <!-- [zh] 开始标签 `<RouterLink>` -->
    <RouterLink
      v-for="(item, index) in list"
      :key="item.productId || `p-${index}`"
      :to="`/product/${item.productId}`"
      class="product-link"
      @click="onProductClick(item)"
    >
      <!-- [zh] 开始标签 `<ProductImage>` -->
      <ProductImage :product="item" :width="140" :height="110" fit="contain" />
      <!-- [zh] 开始标签 `<p>` -->
      <span class="card-index">{{ index + 1 }} · {{ item.verified ? '已核验' : '商品' }}</span>
      <p class="name">{{ item.productName }}</p>
      <!-- [zh] 开始标签 `<p>` -->
      <p class="price">¥{{ formatPrice(item.verified?.price ?? item.minPrice ?? item.price) }}</p>
      <p v-if="item.verified" class="evidence">可售 {{ item.verified.stock }} 件 · {{ item.verified.reason }}</p>
      <span class="detail-link">查看商品 →</span>
    <!-- [zh] 闭合标签 `</RouterLink>` -->
    </RouterLink>
    <!-- [zh] 开始标签 `<p>` -->
    <p v-if="!list.length" class="empty">暂无相关商品</p>
  <!-- [zh] 闭合标签 `</div>` -->
  </div>
</template>

<script setup lang="ts">
import { RouterLink } from 'vue-router';
import ProductImage from '@/components/common/ProductImage.vue';
import { saveAgentConsultProduct } from '@/utils/agentProductConsult';
import { useAuthStore } from '@/stores/auth';

defineProps<{ list: Record<string, any>[] }>();

const authStore = useAuthStore();

const onProductClick = (item: Record<string, any>) => {
  if (!item?.productId || !item?.productName) return;
  saveAgentConsultProduct(
    {
      productId: String(item.productId),
      productName: String(item.productName),
      cover: item.cover ? String(item.cover) : undefined,
      minPrice: item.minPrice
    },
    authStore.userInfo?.userId as string | undefined
  );
};

const formatPrice = (val: unknown) => Number(val ?? 0).toFixed(2);
</script>

/* [zh] 样式声明 */
<style scoped lang="scss">
/* [zh] 样式规则 `@use '@/styles/variables' as *;` */
@use '@/styles/variables' as *;

/* [zh] 样式规则 `.agent-products {` */
.agent-products {
  /* [zh] 样式规则 `display: grid;` */
  display: grid;
  /* [zh] 样式规则 `grid-template-columns: repeat(2, minmax(` */
  grid-template-columns: repeat(2, minmax(0, 1fr));
  /* [zh] 样式规则 `gap: 10px;` */
  gap: 10px;
  /* [zh] 样式规则 `max-height: 280px;` */
  max-height: 280px;
  /* [zh] 样式规则 `overflow-y: auto;` */
  overflow-y: auto;
/* [zh] 样式规则 `}` */
}

/* [zh] 样式规则 `.product-link {` */
.product-link {
  /* [zh] 样式规则 `display: flex;` */
  display: flex;
  /* [zh] 样式规则 `flex-direction: column;` */
  flex-direction: column;
  /* [zh] 样式规则 `gap: 4px;` */
  gap: 4px;
  /* [zh] 样式规则 `padding: 8px;` */
  padding: 8px;
  /* [zh] 样式规则 `border: 1px solid $color-border;` */
  border: 1px solid $color-border;
  /* [zh] 样式规则 `border-radius: $radius-sm;` */
  border-radius: $radius-sm;
  /* [zh] 样式规则 `text-decoration: none;` */
  text-decoration: none;
  /* [zh] 样式规则 `color: inherit;` */
  color: inherit;
  /* [zh] 样式规则 `background: #fafafa;` */
  background: #fafafa;

  /* [zh] 样式规则 `&:hover {` */
  &:hover {
    /* [zh] 样式规则 `border-color: rgba($color-primary, 0.4);` */
    border-color: rgba($color-primary, 0.4);
  /* [zh] 样式规则 `}` */
  }

  /* [zh] 样式规则 `.name {` */
  .name {
    /* [zh] 样式规则 `margin: 0;` */
    margin: 0;
    /* [zh] 样式规则 `font-size: 12px;` */
    font-size: 12px;
    /* [zh] 样式规则 `line-height: 1.35;` */
    line-height: 1.35;
    /* [zh] 样式规则 `color: $color-text-title;` */
    color: $color-text-title;
    /* [zh] 样式规则 `display: -webkit-box;` */
    display: -webkit-box;
    /* [zh] 样式规则 `-webkit-line-clamp: 2;` */
    -webkit-line-clamp: 2;
    /* [zh] 样式规则 `-webkit-box-orient: vertical;` */
    -webkit-box-orient: vertical;
    /* [zh] 样式规则 `overflow: hidden;` */
    overflow: hidden;
  /* [zh] 样式规则 `}` */
  }

  /* [zh] 样式规则 `.price {` */
  .price {
    /* [zh] 样式规则 `margin: 0;` */
    margin: 0;
    /* [zh] 样式规则 `font-size: 13px;` */
    font-size: 13px;
    /* [zh] 样式规则 `font-weight: 600;` */
    font-weight: 600;
    /* [zh] 样式规则 `color: $color-price;` */
    color: $color-price;
  /* [zh] 样式规则 `}` */
  }
/* [zh] 样式规则 `}` */
}

/* [zh] 样式规则 `.empty {` */
.empty {
  /* [zh] 样式规则 `grid-column: 1 / -1;` */
  grid-column: 1 / -1;
  /* [zh] 样式规则 `margin: 0;` */
  margin: 0;
  /* [zh] 样式规则 `font-size: 12px;` */
  font-size: 12px;
  /* [zh] 样式规则 `color: $color-text-muted;` */
  color: $color-text-muted;
  /* [zh] 样式规则 `text-align: center;` */
  text-align: center;
/* [zh] 样式规则 `}` */
}
</style>

<style scoped>.product-link{padding:12px;background:#fff;border-radius:14px}.agent-products{max-height:420px}.card-index{font-size:10px;color:#6f856d}.evidence{font-size:11px;line-height:1.6;color:#758070;margin:0}.detail-link{font-size:11px;color:#426146;padding-top:6px}.product-link .name{font-size:13px;line-height:1.5}.product-link .price{color:#3a583f;font-size:18px}</style>
