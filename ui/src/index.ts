import { axiosInstance } from "@halo-dev/api-client";
import { Dialog, Toast } from "@halo-dev/components";
import { definePlugin } from "@halo-dev/console-shared";
import { markRaw } from "vue";
import RiDatabase2Line from "~icons/ri/database-2-line";

export default definePlugin({
  extensionPoints: {
    "console:dashboard:widgets:internal:quick-action:item:create": () => {
      return [
        {
          id: "refresh-page-cache",
          permissions: ["*"],
          icon: markRaw(RiDatabase2Line),
          title: "刷新页面缓存",
          action: () => {
            Dialog.warning({
              title: "刷新页面缓存",
              description: "此操作会清空所有页面的缓存。",
              async onConfirm() {
                await axiosInstance.delete(
                  "/apis/console.api.cache.halo.run/v1alpha1/caches/page"
                );

                Toast.success("刷新成功");
              },
            });
          },
        },
      ];
    },
  },
});
