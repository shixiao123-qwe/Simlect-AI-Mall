import { onMounted, onUnmounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { orderApi } from '@/api/modules';
import { orderStatusLabel } from '@/constants/backendEnums';
import { launchAlipayPay } from '@/utils/alipayPay';
import { normalizeDisplayPayAmount } from '@/utils/checkout';
import {
  clearPaySuccess,
  isPaySuccessMarked,
  markPaySuccess
} from '@/utils/paySuccessSession';
import { toast } from '@/utils/toast';

export type PaymentPageMode = 'mobile' | 'desktop';

export function usePaymentPage(mode: PaymentPageMode = 'mobile') {
  const route = useRoute();
  const router = useRouter();
  const isMobile = mode === 'mobile';

  const orderInfo = ref<Record<string, any> | null>(null);
  const payAmount = ref(0);
  const payHtml = ref('');
  const payLaunched = ref(false);
  const paySuccess = ref(false);
  const localDemo = ref(false);
  const demoPayLoading = ref(false);
  const demoPaid = ref(false);
  const launching = ref(false);
  const reopening = ref(false);
  const checking = ref(false);
  const loadError = ref('');
  const isProcessing = ref(false);
  let pollTimer: number | undefined;

  const payOrderId = () => String(route.params.payOrderId || '');
  const payLookupId = () => String(route.query.orderId || route.params.payOrderId || '');
  const isAlipayReturn = () => !!route.query.out_trade_no;

  const formatMoney = (v: unknown) => normalizeDisplayPayAmount(v).toFixed(2);

  const resolvePayAmount = (payInfoAmount: unknown, order: Record<string, any> | null) =>
    normalizeDisplayPayAmount(payInfoAmount ?? order?.payTotalAmount ?? order?.amount);

  const isPaidStatus = (status: unknown) => {
    const s = Number(status);
    return s === 1 || s === 2 || s === 3;
  };

  const showPaidSuccess = (info?: Record<string, any> | null) => {
    if (info) {
      orderInfo.value = info;
      demoPaid.value = String(info.channelOrderId || '').startsWith('LOCAL_DEMO_');
    }
    paySuccess.value = true;
    payLaunched.value = false;
    markPaySuccess(payOrderId());
    if (pollTimer) window.clearInterval(pollTimer);
  };

  const loadOrder = async () => {
    orderInfo.value = (await orderApi.getOrderInfo(payOrderId())) || null;
    if (orderInfo.value) {
      payAmount.value = resolvePayAmount(undefined, orderInfo.value);
    }
    if (orderInfo.value && isPaidStatus(orderInfo.value.orderStatus)) {
      showPaidSuccess(orderInfo.value);
      return true;
    }
    return false;
  };

  const launchAlipay = (html: string) => {
    const ok = launchAlipayPay(html, isMobile);
    if (!ok) {
      toast.warning(
        isMobile ? '无法跳转支付宝，请稍后重试' : '无法打开支付窗口，请允许浏览器弹窗后重试'
      );
      return false;
    }
    payLaunched.value = true;
    return true;
  };

  const redirectIfNotPayable = async () => {
    try {
      const info = await orderApi.getOrderInfo(payOrderId());
      if (!info) {
        clearPaySuccess(payOrderId());
        router.replace('/orders');
        return true;
      }
      const status = Number(info.orderStatus);
      if (isPaidStatus(status)) {
        showPaidSuccess(info);
        return true;
      }
      if (status !== 0) {
        clearPaySuccess(payOrderId());
        router.replace('/orders');
        return true;
      }
      return false;
    } catch {
      clearPaySuccess(payOrderId());
      router.replace('/orders');
      return true;
    }
  };

  const startPay = async () => {
    if (!payLookupId()) return;
    if (await redirectIfNotPayable()) return;

    launching.value = true;
    loadError.value = '';
    localDemo.value = false;
    isProcessing.value = false;
    try {
      if (await loadOrder()) return;

      if (isAlipayReturn()) {
        isProcessing.value = true;
        return;
      }

      const data = await orderApi.getPayInfo(payLookupId());
      const html = data?.payInfo;
      if (!html && data?.payOrderId && import.meta.env.DEV && import.meta.env.VITE_PAY_LOCAL_MOCK_ENABLED === 'true') {
        localDemo.value = true;
        payAmount.value = resolvePayAmount(data?.amount, orderInfo.value);
        return;
      }
      if (!html) {
        loadError.value = '获取支付信息失败，请稍后重试';
        return;
      }
      payHtml.value = html;
      payAmount.value = resolvePayAmount(data?.amount, orderInfo.value);
      if (!launchAlipay(html)) {
        loadError.value = isMobile ? '跳转支付宝失败，请点击重试' : '请允许弹窗后点击重试';
      }
    } catch (e: any) {
      if (e?.code === 901) {
        clearPaySuccess(payOrderId());
        router.replace('/login');
        return;
      }
      loadError.value = e?.info || '获取支付信息失败';
    } finally {
      launching.value = false;
    }
  };

  const confirmDemoPayment = async () => {
    if (!localDemo.value || demoPayLoading.value) return;
    demoPayLoading.value = true;
    try {
      const result = await orderApi.confirmLocalDemoPayment(payOrderId());
      if (!result?.simulated) throw new Error('模拟付款响应异常');
      if (!(await loadOrder())) throw new Error('结果待核实，请查看我的订单');
      demoPaid.value = true;
      toast.success('模拟付款成功，未发生真实扣款');
    } catch (e: any) {
      toast.error(e?.info || e?.message || '模拟付款失败，请查看订单状态');
    } finally {
      demoPayLoading.value = false;
    }
  };

  const reopenPayPage = () => {
    if (!payHtml.value) {
      startPay();
      return;
    }
    reopening.value = true;
    try {
      launchAlipay(payHtml.value);
    } finally {
      reopening.value = false;
    }
  };

  const goOrders = () => {
    clearPaySuccess(payOrderId());
    router.replace('/orders');
  };

  const goHome = () => {
    clearPaySuccess(payOrderId());
    router.replace('/');
  };

  const checkPay = async () => {
    checking.value = true;
    try {
      const info = await orderApi.getOrderInfo(payOrderId());
      orderInfo.value = info;
      if (isPaidStatus(info?.orderStatus)) {
        showPaidSuccess(info);
        toast.success('支付成功');
      } else {
        toast.info('订单尚未支付，请完成支付宝付款后再试');
      }
    } finally {
      checking.value = false;
    }
  };

  const pollOrder = async () => {
    if (paySuccess.value) return;
    try {
      const info = await orderApi.getOrderInfo(payOrderId());
      orderInfo.value = info;
      if (isPaidStatus(info?.orderStatus)) {
        showPaidSuccess(info);
        toast.success('支付成功');
      }
    } catch {
      
    }
  };

  const onPageShow = async () => {
    const currentPayOrderId = payOrderId();
    if (isAlipayReturn() || isPaySuccessMarked(currentPayOrderId)) {
      try {
        const info = await orderApi.getOrderInfo(currentPayOrderId);
        if (info) {
          const s = Number(info.orderStatus);
          if (isPaidStatus(s)) {
            showPaidSuccess(info);
            return;
          }
          if (isAlipayReturn() && s !== 0) {
            clearPaySuccess(currentPayOrderId);
            router.replace('/orders');
            return;
          }
        }
      } catch {
        
      }
      if (isAlipayReturn()) {
        clearPaySuccess(currentPayOrderId);
        return;
      }
      clearPaySuccess(currentPayOrderId);
    }
    if (!paySuccess.value && !payLaunched.value) {
      if (await redirectIfNotPayable()) return;
      startPay();
    }
  };

  onMounted(async () => {
    const currentPayOrderId = payOrderId();

    if (isAlipayReturn()) {
      await loadOrder();
      if (orderInfo.value) {
        const s = Number(orderInfo.value.orderStatus);
        if (isPaidStatus(s)) {
          showPaidSuccess(orderInfo.value);
          return;
        }
        if (s !== 0) {
          clearPaySuccess(currentPayOrderId);
          router.replace('/orders');
          return;
        }
      }
      pollTimer = window.setInterval(pollOrder, 4000);
      window.addEventListener('pageshow', onPageShow);
      return;
    }

    if (isPaySuccessMarked(currentPayOrderId)) {
      try {
        const info = await orderApi.getOrderInfo(currentPayOrderId);
        if (info && isPaidStatus(info.orderStatus)) {
          showPaidSuccess(info);
          return;
        }
      } catch {
        
      }
      clearPaySuccess(currentPayOrderId);
    }

    if (await redirectIfNotPayable()) return;

    await startPay();
    pollTimer = window.setInterval(pollOrder, 4000);
    window.addEventListener('pageshow', onPageShow);
  });

  onUnmounted(() => {
    if (pollTimer) window.clearInterval(pollTimer);
    window.removeEventListener('pageshow', onPageShow);
  });

  return {
    route,
    orderInfo,
    payAmount,
    payLaunched,
    paySuccess,
    localDemo,
    demoPayLoading,
    demoPaid,
    confirmDemoPayment,
    launching,
    reopening,
    checking,
    loadError,
    isProcessing,
    payOrderId,
    formatMoney,
    orderStatusLabel,
    startPay,
    reopenPayPage,
    goOrders,
    goHome,
    checkPay
  };
}
