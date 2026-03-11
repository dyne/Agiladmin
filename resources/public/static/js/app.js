(function () {
  function initTabGroups(root) {
    root.querySelectorAll("[data-tab-group]").forEach(function (group) {
      var triggers = Array.prototype.slice.call(
        group.querySelectorAll("[data-tab-trigger]")
      );
      var panels = Array.prototype.slice.call(
        group.querySelectorAll("[data-tab-panel]")
      );

      function activate(id) {
        triggers.forEach(function (trigger) {
          var active = trigger.getAttribute("data-tab-trigger") === id;
          trigger.classList.toggle("tab-active", active);
          trigger.setAttribute("aria-selected", active ? "true" : "false");
        });

        panels.forEach(function (panel) {
          panel.hidden = panel.getAttribute("data-tab-panel") !== id;
        });
      }

      triggers.forEach(function (trigger) {
        trigger.addEventListener("click", function () {
          activate(trigger.getAttribute("data-tab-trigger"));
        });
      });

      if (triggers[0]) {
        activate(triggers[0].getAttribute("data-tab-trigger"));
      }
    });
  }

  function initNavToggles(root) {
    root.querySelectorAll("[data-nav-toggle]").forEach(function (toggle) {
      if (toggle.dataset.bound === "true") {
        return;
      }

      var target = document.getElementById(toggle.getAttribute("data-nav-toggle"));
      if (!target) {
        return;
      }

      toggle.dataset.bound = "true";
      toggle.addEventListener("click", function () {
        var expanded = toggle.getAttribute("aria-expanded") === "true";
        toggle.setAttribute("aria-expanded", expanded ? "false" : "true");
        target.classList.toggle("hidden", expanded);
      });
    });
  }

  function boot(root) {
    initTabGroups(root);
    initNavToggles(root);
  }

  document.addEventListener("DOMContentLoaded", function () {
    boot(document);
  });

  document.body.addEventListener("htmx:afterSwap", function (event) {
    boot(event.target);
  });
})();
