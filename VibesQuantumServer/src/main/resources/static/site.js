"use strict";
(async () => {
  const label = document.getElementById("healthLabel");
  const dot = document.getElementById("healthDot");
  try {
    const response = await fetch("/api/v1/health", { headers: { Accept: "application/json" }, cache: "no-store" });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const data = await response.json();
    label.textContent = data.status === "online" ? "Service online" : "Health endpoint responded";
    dot.classList.toggle("ok", data.status === "online");
  } catch (_) {
    label.textContent = "Health check unavailable";
  }
})();
