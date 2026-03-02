import { createApp } from "vue";
import App from "./App.vue";

createApp(App).mount("#app");

if ("serviceWorker" in navigator) {
  window.addEventListener("load", () => {
    navigator.serviceWorker.register("/sw.js").then(
      (reg) => console.log("[sw] registered, scope:", reg.scope),
      (err) => console.warn("[sw] registration failed:", err)
    );
  });
}
