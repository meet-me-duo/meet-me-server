export function createBrowserObservation(allowedOrigins) {
  const allowed = new Set(allowedOrigins);
  const contexts = new WeakSet();
  const pages = new WeakSet();
  let routesIntercepted = 0;
  let unexpectedOrigins = 0;
  const increment = value => Math.min(Number.MAX_SAFE_INTEGER, value + 1);
  function observeUrl(value) {
    try {
      if (!allowed.has(new URL(value).origin)) unexpectedOrigins = increment(unexpectedOrigins);
    } catch { unexpectedOrigins = increment(unexpectedOrigins); }
  }
  function wrapMethods(target) {
    for (const method of ['route', 'routeFromHAR', 'routeWebSocket']) {
      if (typeof target[method] !== 'function') continue;
      const original = target[method];
      target[method] = function (...args) {
        routesIntercepted = increment(routesIntercepted);
        return Reflect.apply(original, this, args);
      };
    }
  }
  function wrapPage(page) {
    if (pages.has(page)) return;
    pages.add(page);
    wrapMethods(page);
    page.on('websocket', socket => {
      try { observeUrl(socket.url()); } catch { unexpectedOrigins = increment(unexpectedOrigins); }
    });
  }
  return {
    wrapContext(context) {
      if (contexts.has(context)) return context;
      contexts.add(context);
      wrapMethods(context);
      context.on('request', request => {
        try { observeUrl(request.url()); } catch { unexpectedOrigins = increment(unexpectedOrigins); }
      });
      for (const page of context.pages()) wrapPage(page);
      context.on('page', wrapPage);
      return context;
    },
    snapshot() { return { routesIntercepted, unexpectedOrigins }; },
  };
}
