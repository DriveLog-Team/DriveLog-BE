(() => {
    const credential = location.hash.slice(1);
    const missingApp = new URLSearchParams(location.search).get("app") === "missing";
    history.replaceState(null, "", location.pathname);
    const status = document.querySelector("[role=status]");
    const match = /^([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})\.([A-Za-z0-9_-]{43})$/.exec(credential);
    if (!match) {
        status.textContent = "차량이나 보관함에서 발급한 QR을 다시 스캔하세요.";
        return;
    }

    const [, id, code] = match;
    const form = document.querySelector("form");
    form.elements.id.value = id;
    form.elements.code.value = code;
    form.querySelector("button").disabled = false;
    if (!/Android/i.test(navigator.userAgent)) return;

    const story = document.querySelector("#story");
    const fallback = location.origin + location.pathname + "?app=missing#" + credential;
    story.href = "intent://story/" + id + "#Intent;scheme=drivelog;package=kr.drivelog.phone;S.ticket="
        + code + ";S.browser_fallback_url=" + encodeURIComponent(fallback) + ";end";
    story.hidden = false;
    if (missingApp) status.textContent = "DriveLog 스토리 앱을 설치한 뒤 다시 누르거나 MP4를 내려받으세요.";
})();
