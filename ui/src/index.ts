import { axiosInstance, consoleApiClient } from "@halo-dev/api-client";
import { Dialog, Toast } from "@halo-dev/components";
import { definePlugin } from "@halo-dev/console-shared";

function createCleanupButton() {
  const button = document.createElement("button");
  button.innerHTML = `<svg width="1.5rem" fill="#fff" height="1.5rem" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor"><path d="M5 12.5C5 12.8134 5.46101 13.3584 6.53047 13.8931C7.91405 14.5849 9.87677 15 12 15C14.1232 15 16.0859 14.5849 17.4695 13.8931C18.539 13.3584 19 12.8134 19 12.5V10.3287C17.35 11.3482 14.8273 12 12 12C9.17273 12 6.64996 11.3482 5 10.3287V12.5ZM19 15.3287C17.35 16.3482 14.8273 17 12 17C9.17273 17 6.64996 16.3482 5 15.3287V17.5C5 17.8134 5.46101 18.3584 6.53047 18.8931C7.91405 19.5849 9.87677 20 12 20C14.1232 20 16.0859 19.5849 17.4695 18.8931C18.539 18.3584 19 17.8134 19 17.5V15.3287ZM3 17.5V7.5C3 5.01472 7.02944 3 12 3C16.9706 3 21 5.01472 21 7.5V17.5C21 19.9853 16.9706 22 12 22C7.02944 22 3 19.9853 3 17.5ZM12 10C14.1232 10 16.0859 9.58492 17.4695 8.89313C18.539 8.3584 19 7.81342 19 7.5C19 7.18658 18.539 6.6416 17.4695 6.10687C16.0859 5.41508 14.1232 5 12 5C9.87677 5 7.91405 5.41508 6.53047 6.10687C5.46101 6.6416 5 7.18658 5 7.5C5 7.81342 5.46101 8.3584 6.53047 8.89313C7.91405 9.58492 9.87677 10 12 10Z"></path></svg>`;
  button.style.position = "fixed";
  button.style.right = "2rem";
  button.style.bottom = "5rem";
  button.style.width = "3rem";
  button.style.height = "3rem";
  button.style.borderRadius = "50%";
  button.style.backgroundColor = "rgba(0, 0, 0, 0.5)";
  button.style.display = "flex";
  button.style.alignItems = "center";
  button.style.justifyContent = "center";
  button.style.transition = "background-color 0.3s";
  button.title = "刷新页面缓存";
  button.addEventListener("click", () => {
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
  });

  button.addEventListener("mouseover", () => {
    button.style.backgroundColor = "rgba(0, 0, 0, 0.7)";
  });

  button.addEventListener("mouseout", () => {
    button.style.backgroundColor = "rgba(0, 0, 0, 0.5)";
  });

  document.body.appendChild(button);
}

if (location.pathname.startsWith("/console")) {
  consoleApiClient.user.getPermissions({ name: "-" }).then((response) => {
    if (response.data.uiPermissions.includes("*")) {
      createCleanupButton();
    }
  });
}

export default definePlugin({});
