export function uniqueProducts(items: Record<string, any>[], limit = 120): Record<string, any>[] {
  const seen = new Set<string>();
  return items.filter(item => {
    const id = String(item?.productId || '');
    if (!id || seen.has(id)) return false;
    seen.add(id); return true;
  }).slice(0, limit);
}
export function afterSaleProgress(phase: string, type: string) {
  const returned = type === 'return_refund';
  const steps = returned ? ['提交申请', '人工审核', '寄回商品', '仓库验货', '退款处理'] : ['提交申请', '人工审核', '退款处理'];
  const positions: Record<string, number> = returned
    ? { PENDING_REVIEW: 1, WAIT_RETURN: 2, WAIT_INSPECTION: 3, READY_REFUND: 4, REFUNDING: 4, REFUND_FAILED: 4, COMPLETED: 5, REJECTED: 1 }
    : { PENDING_REVIEW: 1, READY_REFUND: 2, REFUNDING: 2, REFUND_FAILED: 2, COMPLETED: 3, REJECTED: 1 };
  const tips: Record<string, string> = {
    PENDING_REVIEW: '等待管理员审核，可在操作记录中查看处理进展。', WAIT_RETURN: '下一步：核对退货地址，填写寄回物流。',
    WAIT_INSPECTION: '物流已提交，等待仓库核验与验货。', READY_REFUND: '业务条件已满足，等待管理员执行退款；尚未退款。',
    REFUNDING: '退款处理中，请勿重复提交。', REFUND_FAILED: '退款尚未完成，请等待管理员核实后重试。',
    REJECTED: '申请已驳回，请查看审核或验货意见。', COMPLETED: '流程已完成，可查看退款结果和完整操作记录。'
  };
  return { steps, active: positions[phase] ?? 0, tip: tips[phase] || '状态暂不可识别，请刷新或查看操作记录。', error: ['REJECTED', 'REFUND_FAILED'].includes(phase) };
}
