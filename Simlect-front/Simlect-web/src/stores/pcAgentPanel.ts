import { defineStore } from 'pinia';
import { ref } from 'vue';

export const usePcAgentPanelStore = defineStore('pcAgentPanel', () => {
  const visible = ref(false);

  const fromProduct = ref(false);
  const presetMessage = ref('');

  function open(options?: { fromProduct?: boolean; presetMessage?: string }) {
    fromProduct.value = !!options?.fromProduct;
    presetMessage.value = options?.presetMessage || '';
    visible.value = true;
  }

  function consumePresetMessage() { const text = presetMessage.value; presetMessage.value = ''; return text; }

  function close() {
    presetMessage.value = '';
    visible.value = false;
    fromProduct.value = false;
  }

  function toggle() {
    visible.value = !visible.value;
    if (!visible.value) { fromProduct.value = false; presetMessage.value = ''; }
  }

  function consumeFromProduct() {
    const v = fromProduct.value;
    fromProduct.value = false;
    return v;
  }

  return { visible, fromProduct, presetMessage, open, close, toggle, consumeFromProduct, consumePresetMessage };
});
