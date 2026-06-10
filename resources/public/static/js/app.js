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

        window.requestAnimationFrame(function () {
          initPlotlyCharts(group);
          resizePlotlyCharts(group);
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

  function plotlyConfig() {
    return {
      responsive: true,
      displaylogo: false,
      modeBarButtonsToRemove: ["select2d", "lasso2d", "autoScale2d"],
    };
  }

  function canRenderChart(chart) {
    return Boolean(chart && chart.getClientRects && chart.getClientRects().length);
  }

  function plotlyFallback(chart, message) {
    if (!chart) {
      return;
    }

    chart.innerHTML =
      '<p class="rounded-box border border-dashed border-base-300 px-4 py-6 text-sm text-base-content/70">' +
      message +
      "</p>";
  }

  function renderPlotlyChart(chart) {
    if (!chart || chart.dataset.plotlyInitialized === "true") {
      return;
    }

    if (!window.Plotly) {
      plotlyFallback(chart, "Plotly is not available in this browser session.");
      return;
    }

    if (!canRenderChart(chart)) {
      return;
    }

    var rawSpec = chart.getAttribute("data-plotly-spec") || "";
    if (rawSpec === "") {
      plotlyFallback(chart, "No chart data was provided.");
      return;
    }

    var spec;
    try {
      spec = JSON.parse(rawSpec);
    } catch (error) {
      plotlyFallback(chart, "This chart could not be parsed.");
      return;
    }

    chart.dataset.plotlyInitialized = "true";
    window.Plotly.newPlot(chart, spec.data || [], spec.layout || {}, plotlyConfig());
  }

  function schedulePlotlyChart(chart) {
    if (
      !chart ||
      chart.dataset.plotlyInitialized === "true" ||
      chart.dataset.plotlyScheduled === "true"
    ) {
      return;
    }

    chart.dataset.plotlyScheduled = "true";
    var render = function () {
      chart.dataset.plotlyScheduled = "false";
      renderPlotlyChart(chart);
    };

    if (window.requestIdleCallback) {
      window.requestIdleCallback(render, { timeout: 250 });
    } else {
      window.setTimeout(render, 0);
    }
  }

  function resizePlotlyCharts(root) {
    if (!window.Plotly || !window.Plotly.Plots) {
      return;
    }

    Array.prototype.slice
      .call(root.querySelectorAll("[data-plotly-chart][data-plotly-initialized='true']"))
      .forEach(function (chart) {
        if (canRenderChart(chart)) {
          window.Plotly.Plots.resize(chart);
        }
      });
  }

  function initPlotlyCharts(root) {
    Array.prototype.slice
      .call(root.querySelectorAll("[data-plotly-chart]"))
      .forEach(function (chart) {
        schedulePlotlyChart(chart);
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

    Array.prototype.slice
      .call(document.querySelectorAll("[data-theme-invert]"))
      .forEach(function (image) {
        image.classList.toggle("invert", theme === darkTheme);
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

  function showPageLoading() {
    Array.prototype.slice
      .call(document.querySelectorAll("[data-page-loading]"))
      .forEach(function (indicator) {
        indicator.style.display = "flex";
      });
  }

  function skipsPageLoading(element) {
    return Boolean(
      element &&
        element.closest &&
        element.closest("[data-skip-page-loading]")
    );
  }

  function afterNextPaint(callback) {
    window.requestAnimationFrame(function () {
      window.requestAnimationFrame(function () {
        callback();
      });
    });
  }

  function initPageLoading(root) {
    Array.prototype.slice
      .call(root.querySelectorAll("a[href]"))
      .forEach(function (link) {
        if (link.dataset.loadingBound === "true") {
          return;
        }

        var href = link.getAttribute("href") || "";
        var target = link.getAttribute("target");
        var rel = link.getAttribute("rel") || "";
        if (
          href === "" ||
          href.indexOf("#") === 0 ||
          href.indexOf("mailto:") === 0 ||
          href.indexOf("tel:") === 0 ||
          link.hasAttribute("download") ||
          skipsPageLoading(link) ||
          target === "_blank" ||
          rel.indexOf("external") !== -1
        ) {
          return;
        }

        link.dataset.loadingBound = "true";
        link.addEventListener("click", function (event) {
          if (
            event.defaultPrevented ||
            event.metaKey ||
            event.ctrlKey ||
            event.shiftKey ||
            event.altKey ||
            event.button !== 0
          ) {
            return;
          }
          event.preventDefault();
          showPageLoading();
          afterNextPaint(function () {
            window.location.assign(href);
          });
        });
      });

    Array.prototype.slice
      .call(root.querySelectorAll("form"))
      .forEach(function (form) {
        if (form.dataset.loadingBound === "true") {
          return;
        }

        form.dataset.loadingBound = "true";
        form.addEventListener("submit", function (event) {
          if (!form.hasAttribute("hx-post") && !form.hasAttribute("hx-get")) {
            event.preventDefault();
            showPageLoading();
            afterNextPaint(function () {
              HTMLFormElement.prototype.submit.call(form);
            });
          }
        });
      });
  }

  function initUploadProgress(root) {
    root.querySelectorAll("form").forEach(function (form) {
      if (form.dataset.uploadProgressBound === "true") {
        return;
      }

      var progress = form.querySelector("[data-upload-progress]");
      var label = form.querySelector("[data-upload-progress-label]");
      if (!progress || !label) {
        return;
      }

      function setProgress(percent) {
        var value = Math.max(0, Math.min(100, Math.round(percent)));
        progress.value = value;
        label.textContent = value + "%";
      }

      function startProgress() {
        progress.removeAttribute("value");
        label.textContent = "Uploading...";
      }

      function resetProgress() {
        setProgress(0);
      }

      function failProgress() {
        setProgress(0);
        label.textContent = "Upload failed. Please try again.";
      }

      form.dataset.uploadProgressBound = "true";
      resetProgress();

      form.addEventListener("submit", function (event) {
        if (event.defaultPrevented || !form.checkValidity()) {
          return;
        }
        startProgress();
      });

      form.addEventListener("htmx:beforeRequest", function (event) {
        if (event.target !== form) {
          return;
        }
        startProgress();
      });

      form.addEventListener("htmx:xhr:progress", function (event) {
        if (event.target !== form) {
          return;
        }

        var detail = event.detail || {};
        var total = Number(detail.total || 0);
        var loaded = Number(detail.loaded || 0);
        if (total > 0) {
          setProgress((loaded / total) * 100);
        }
      });

      form.addEventListener("htmx:afterRequest", function (event) {
        if (event.target !== form) {
          return;
        }

        var successful = event.detail && event.detail.successful;
        if (successful) {
          setProgress(100);
          window.setTimeout(resetProgress, 300);
        } else {
          failProgress();
        }
      });
    });
  }

  function boot(root) {
    initTabGroups(root);
    initNavToggles(root);
    initTextFilters(root);
    initPlotlyCharts(root);
    initThemeToggle(root);
    initPageLoading(root);
    initUploadProgress(root);
  }

  document.addEventListener("DOMContentLoaded", function () {
    boot(document);
  });

  document.addEventListener("htmx:load", function (event) {
    boot(event.target);
  });

  document.addEventListener("htmx:beforeRequest", function (event) {
    if (skipsPageLoading(event.detail && event.detail.elt)) {
      return;
    }
    showPageLoading();
  });

  document.addEventListener("htmx:afterRequest", function () {
    Array.prototype.slice
      .call(document.querySelectorAll("[data-page-loading]"))
      .forEach(function (indicator) {
        indicator.style.display = "none";
      });

    resizePlotlyCharts(document);
  });
})();
