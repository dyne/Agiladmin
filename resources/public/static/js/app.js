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

  function initTextFilters(root) {
    root.querySelectorAll("[data-text-filter]").forEach(function (filter) {
      if (filter.dataset.bound === "true") {
        return;
      }

      var filterId = filter.getAttribute("data-text-filter");
      var input = filter.querySelector("[data-text-filter-input]");
      var clear = filter.querySelector("[data-text-filter-clear]");
      var list = document.querySelector('[data-text-filter-list="' + filterId + '"]');
      if (!input || !clear || !list) {
        return;
      }

      var items = Array.prototype.slice.call(
        list.querySelectorAll("[data-text-filter-item]")
      );
      var emptyState = list.querySelector("[data-text-filter-empty]");

      function update() {
        var query = input.value.toLowerCase().trim();
        var visibleCount = 0;

        items.forEach(function (item) {
          var value = (item.getAttribute("data-text-filter-value") || "").toLowerCase();
          var visible = query === "" || value.indexOf(query) !== -1;
          item.classList.toggle("hidden", !visible);
          if (visible) {
            visibleCount += 1;
          }
        });

        if (emptyState) {
          emptyState.classList.toggle("hidden", visibleCount !== 0);
        }
        clear.disabled = input.value === "";
      }

      filter.dataset.bound = "true";
      input.addEventListener("input", update);
      clear.addEventListener("click", function () {
        input.value = "";
        update();
        input.focus();
      });
      update();
    });
  }

  function applyTheme(theme) {
    var body = document.body;
    if (!body) {
      return;
    }

    var lightTheme = body.getAttribute("data-theme-light") || "nord";
    var darkTheme = body.getAttribute("data-theme-dark") || "dim";

    body.setAttribute("data-theme", theme);
    try {
      window.localStorage.setItem("agiladmin-theme", theme);
    } catch (error) {
      // Ignore storage failures and keep the in-memory theme.
    }

    Array.prototype.slice
      .call(document.querySelectorAll("[data-theme-toggle]"))
      .forEach(function (toggle) {
        toggle.checked = theme === darkTheme;
      });

    Array.prototype.slice
      .call(document.querySelectorAll("[data-theme-logo]"))
      .forEach(function (logo) {
        var src =
          theme === darkTheme
            ? logo.getAttribute("data-theme-logo-dark")
            : logo.getAttribute("data-theme-logo-light");
        if (src) {
          logo.setAttribute("src", src);
        }
      });
  }

  function initThemeToggle(root) {
    var body = document.body;
    if (!body) {
      return;
    }

    var lightTheme = body.getAttribute("data-theme-light") || "nord";
    var darkTheme = body.getAttribute("data-theme-dark") || "dim";
    var initialTheme = body.getAttribute("data-theme") || lightTheme;

    try {
      initialTheme = window.localStorage.getItem("agiladmin-theme") || initialTheme;
    } catch (error) {
      // Keep the server-provided theme when storage is unavailable.
    }

    Array.prototype.slice
      .call(root.querySelectorAll("[data-theme-toggle]"))
      .forEach(function (toggle) {
        if (toggle.dataset.bound === "true") {
          return;
        }

        toggle.dataset.bound = "true";
        toggle.addEventListener("change", function () {
          applyTheme(toggle.checked ? darkTheme : lightTheme);
        });
      });

    applyTheme(initialTheme === darkTheme ? darkTheme : lightTheme);
  }

  function boot(root) {
    initTabGroups(root);
    initNavToggles(root);
    initTextFilters(root);
    initThemeToggle(root);
  }

  document.addEventListener("DOMContentLoaded", function () {
    boot(document);
  });

  document.addEventListener("htmx:afterSwap", function (event) {
    boot(event.target);
  });
})();
