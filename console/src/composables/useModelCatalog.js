import { shallowRef } from "vue";
import { request } from "../api/http.js";

// Share saved configuration so every chat shows the current default immediately.
export const modelCatalog = shallowRef(null);
let loading;
export function loadModelCatalog() {
  if (!loading)
    loading = request("/api/v1/models")
      .then((value) => {
        modelCatalog.value = value;
        return value;
      })
      .finally(() => {
        loading = null;
      });
  return loading;
}
