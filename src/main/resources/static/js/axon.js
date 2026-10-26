// AXON · comportamiento mínimo del navegador. Archivo externo para poder cerrar la política de
// contenido (CSP) sin scripts en línea.
document.addEventListener('click', function (event) {
  var trigger = event.target.closest('[data-abrir-dialogo]');
  if (!trigger) return;
  var dialog = document.getElementById(trigger.getAttribute('data-abrir-dialogo'));
  if (dialog && typeof dialog.showModal === 'function') dialog.showModal();
});

// Visor del documento de un registro (HU-04, HU-11). Pide el archivo a /documento/{id}, que valida
// la sesión y la titularidad, y lo muestra desde memoria: el archivo no queda con dirección propia.
// Tres estados en data-estado: cargando, listo y error. Si falla, los datos del registro siguen ahí.
(function () {
  function mostrar(visor, estado) {
    visor.setAttribute('data-estado', estado);
    visor.querySelector('[data-visor-cargando]').hidden = estado !== 'cargando';
    visor.querySelector('[data-visor-documento]').hidden = estado !== 'listo';
    visor.querySelector('[data-visor-error]').hidden = estado !== 'error';
  }

  function cargar(visor) {
    var contenido = visor.querySelector('[data-visor-documento]');
    if (visor._url) URL.revokeObjectURL(visor._url);
    contenido.replaceChildren();
    mostrar(visor, 'cargando');

    fetch(visor.getAttribute('data-src'), { credentials: 'same-origin', cache: 'no-store' })
      .then(function (respuesta) {
        // Una sesión vencida redirige al login: eso tampoco es el documento.
        if (!respuesta.ok || respuesta.redirected) throw new Error('documento ' + respuesta.status);
        return respuesta.blob();
      })
      .then(function (archivo) {
        visor._url = URL.createObjectURL(archivo);
        var pdf = visor.getAttribute('data-tipo') === 'pdf';
        var el = document.createElement(pdf ? 'iframe' : 'img');
        el.setAttribute('data-testid', 'visor-archivo');
        if (pdf) {
          el.title = 'Documento del registro';
          mostrar(visor, 'listo');
        } else {
          el.alt = 'Documento del registro';
          el.onload = function () { mostrar(visor, 'listo'); };
          el.onerror = function () { mostrar(visor, 'error'); };
        }
        el.src = visor._url;
        contenido.appendChild(el);
      })
      .catch(function () { mostrar(visor, 'error'); });
  }

  document.addEventListener('click', function (event) {
    var boton = event.target.closest('[data-visor-reintentar]');
    if (boton) cargar(boton.closest('[data-visor]'));
  });

  // En la consulta del profesional el documento se pide solo cuando abre el registro.
  document.querySelectorAll('[data-visor]:not([data-visor-diferido])').forEach(cargar);
  document.querySelectorAll('[data-visor-diferido]').forEach(function (visor) {
    var abrir = visor.closest('details');
    if (!abrir) return;
    abrir.addEventListener('toggle', function () {
      if (abrir.open && !visor._pedido) {
        visor._pedido = true;
        cargar(visor);
      }
    });
  });
})();

// Agregar un registro (HU-03, UI.md 07). La foto entra por el input de archivo con capture, que abre
// la cámara del sistema sin pedir HTTPS; nada de getUserMedia. «Otro archivo» no lleva capture, así
// el teléfono ofrece la galería, sus archivos o un PDF (DEC-27). Queda adjunto uno solo, y el botón
// dice si el registro se guardará con documento o sin él. Tipo y tope de 10 MB los vuelve a revisar
// el servidor por el contenido: aquí solo se avisa antes de mandar el archivo. Corregir (H8, DEC-40)
// usa el mismo formulario: trae sus textos del botón y, si el registro ya tiene documento, no hay captura.
(function () {
  var form = document.querySelector('[data-agregar]');
  if (!form) return;
  var MAX_BYTES = 10 * 1024 * 1024;
  var TIPOS = ['image/jpeg', 'image/png', 'image/webp', 'application/pdf'];
  var foto = form.querySelector('#foto');
  if (!foto) return;
  var otro = form.querySelector('#archivo');
  var sinDocumento = form.querySelector('[data-sin-documento]');
  var conDocumento = form.querySelector('[data-con-documento]');
  var vista = form.querySelector('[data-adjunto-vista]');
  var hoja = form.querySelector('[data-adjunto-hoja]');
  var texto = form.querySelector('[data-adjunto-texto]');
  var avisoPeso = form.querySelector('[data-aviso-peso]');
  var avisoTipo = form.querySelector('[data-aviso-tipo]');
  var guardar = form.querySelector('[data-guardar]');
  var textoSin = form.getAttribute('data-texto-sin') || 'Guardar sin documento';
  var textoCon = form.getAttribute('data-texto-con') || 'Guardar con documento';
  var url = null;

  function mostrarHoja() {
    vista.hidden = true;
    hoja.hidden = false;
  }

  function actualizar() {
    var input = foto.files.length ? foto : (otro.files.length ? otro : null);
    var archivo = input ? input.files[0] : null;
    if (url) URL.revokeObjectURL(url);
    url = null;
    vista.removeAttribute('src');
    sinDocumento.hidden = !!archivo;
    conDocumento.hidden = !archivo;
    guardar.textContent = archivo ? textoCon : textoSin;
    if (!archivo) return;
    var imagen = archivo.type.indexOf('image/') === 0;
    texto.textContent = input === foto ? 'Foto tomada'
      : (imagen ? 'Imagen adjunta · ' : 'PDF adjunto · ') + archivo.name;
    if (imagen) {
      // Vista previa desde memoria; si el navegador no sabe dibujarla, queda la hoja.
      url = URL.createObjectURL(archivo);
      vista.onerror = mostrarHoja;
      vista.src = url;
      vista.hidden = false;
      hoja.hidden = true;
    } else {
      mostrarHoja();
    }
  }

  function alElegir(input, elOtro) {
    input.addEventListener('change', function () {
      avisoPeso.hidden = true;
      avisoTipo.hidden = true;
      var archivo = input.files[0];
      if (!archivo) {
        actualizar();
        return;
      }
      // Si el navegador no dice el tipo, decide el servidor.
      if (archivo.type && TIPOS.indexOf(archivo.type) < 0) {
        input.value = '';
        avisoTipo.hidden = false;
      } else if (archivo.size > MAX_BYTES) {
        input.value = '';
        avisoPeso.hidden = false;
      } else {
        elOtro.value = '';
      }
      actualizar();
    });
  }

  alElegir(foto, otro);
  alElegir(otro, foto);
  form.querySelector('[data-quitar-documento]').addEventListener('click', function () {
    foto.value = '';
    otro.value = '';
    actualizar();
    foto.focus();
  });
  // Al volver con el botón atrás, el navegador puede traer el archivo ya elegido.
  actualizar();
})();

// Compartir (HU-07, UI.md 09). Mientras la paciente marca, el resumen dice qué queda fuera y por
// cuánto tiempo, con las mismas frases que arma el servidor (PermissionService.resumen). Sin ninguna
// categoría el botón se apaga; el servidor igual lo rechaza si llega vacío.
(function () {
  var form = document.querySelector('[data-compartir]');
  if (!form) return;
  var casillas = Array.prototype.slice.call(form.querySelectorAll('input[name="alcance"]'));
  var quedaFuera = form.querySelector('[data-queda-fuera]');
  var nota = form.querySelector('[data-queda-fuera-nota]');
  var vigenciaTexto = form.querySelector('[data-vigencia-texto]');
  var generar = form.querySelector('[data-generar]');
  var generarTexto = form.querySelector('[data-generar-texto]');

  function unir(nombres) {
    if (nombres.length === 1) return nombres[0];
    return nombres.slice(0, -1).join(', ') + ' y ' + nombres[nombres.length - 1];
  }

  function actualizar() {
    var fuera = casillas.filter(function (c) { return !c.checked; })
      .map(function (c) { return c.getAttribute('data-corto'); });
    var marcadas = casillas.length - fuera.length;
    if (fuera.length === 0) {
      quedaFuera.textContent = 'Nada. Le muestras la cartilla completa.';
      nota.textContent = 'Puedes desmarcar lo que no haga falta para esta consulta.';
    } else {
      var texto = unir(fuera);
      quedaFuera.textContent = texto.charAt(0).toUpperCase() + texto.slice(1);
      nota.textContent = 'El médico no lo ve ni sabe que existe.';
    }
    var vigencia = form.querySelector('input[name="vigencia"]:checked');
    if (vigencia) vigenciaTexto.textContent = vigencia.getAttribute('data-largo');
    generar.disabled = marcadas === 0;
    generarTexto.textContent = marcadas === 0 ? 'Marca al menos una categoría' : 'Generar el código';
  }

  form.addEventListener('change', actualizar);
  actualizar();
})();

// Contadores de los permisos activos (UI.md 10 y 11). Cuentan desde los segundos que dijo el servidor,
// no desde el reloj del teléfono. Al llegar a cero la página se recarga y el servidor dice el estado:
// la pantalla no decide que un permiso venció (DEC-28, DEC-29).
(function () {
  var contadores = Array.prototype.slice.call(document.querySelectorAll('[data-contador]'));
  if (!contadores.length) return;
  var inicio = Date.now();

  function formato(s) {
    if (s <= 0) return 'vencido';
    if (s >= 3600) {
      var m = Math.floor((s % 3600) / 60);
      return Math.floor(s / 3600) + ' h ' + (m < 10 ? '0' : '') + m + ' min';
    }
    var min = Math.floor(s / 60), seg = s % 60;
    return (min < 10 ? '0' : '') + min + ':' + (seg < 10 ? '0' : '') + seg;
  }

  function tic() {
    var pasados = Math.floor((Date.now() - inicio) / 1000);
    var vencioAlguno = false;
    contadores.forEach(function (c) {
      var total = Number(c.getAttribute('data-total')) || 1;
      var quedan = Math.max(0, Number(c.getAttribute('data-restante')) - pasados);
      var texto = c.querySelector('[data-restante-texto]');
      if (texto) texto.textContent = formato(quedan);
      var barra = (c.closest('.permit, .code-card') || c).querySelector('[data-barra]');
      if (barra) barra.style.width = (Math.min(1, quedan / total) * 100).toFixed(2) + '%';
      // La consulta del profesional no se recarga: de su corte se encarga la pregunta de estado (DEC-30).
      if (quedan === 0 && !c.hasAttribute('data-sin-recarga')) vencioAlguno = true;
    });
    if (vencioAlguno) {
      clearInterval(reloj);
      setTimeout(function () { window.location.reload(); }, 1000);
    }
  }

  var reloj = setInterval(tic, 1000);
})();

// Consulta del profesional (HU-08, CR-15, DEC-30). Cada 5 s pregunta al servidor si el permiso sigue
// vigente. Si se revocó o venció, quita la cartilla del documento sin recargar y dice por qué; la
// pantalla solo se entera, el corte lo decide el servidor, que además vuelve a validar cada solicitud.
(function () {
  var consulta = document.querySelector('[data-consulta]');
  if (!consulta) return;
  var url = consulta.getAttribute('data-estado-url');
  var contenido = consulta.querySelector('[data-consulta-contenido]');
  var corte = consulta.querySelector('[data-consulta-corte]');

  function cortar(titulo, texto, motivo) {
    clearInterval(reloj);
    // Los documentos ya abiertos tampoco quedan en memoria (guardián, H4).
    contenido.querySelectorAll('[data-visor]').forEach(function (v) { if (v._url) URL.revokeObjectURL(v._url); });
    contenido.remove();
    corte.querySelector('[data-corte-titulo]').textContent = titulo;
    corte.querySelector('[data-corte-texto]').textContent = texto;
    corte.querySelector('[data-testid="consulta-cortada"]').setAttribute('data-motivo', motivo);
    corte.hidden = false;
  }

  function preguntar() {
    fetch(url, { credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json' } })
      .then(function (respuesta) {
        // Sin sesión, la cadena del profesional redirige al login: eso tampoco es una consulta vigente.
        if (respuesta.redirected || respuesta.status === 401 || respuesta.status === 403 || respuesta.status === 404) {
          cortar('La sesión se cerró', 'Vuelva a entrar con su cuenta para canjear un código.', 'sesion');
          return null;
        }
        return respuesta.ok ? respuesta.json() : null;
      })
      .then(function (estado) {
        if (estado && !estado.vigente) cortar(estado.titulo, estado.texto, estado.motivo);
      })
      .catch(function () { /* Sin red no se decide nada: la próxima pregunta lo intenta de nuevo. */ });
  }

  var reloj = setInterval(preguntar, 5000);
})();

// Ficha de emergencia (HU-06, UI.md 12). Mientras la paciente marca, el resumen dice qué verá urgencias,
// con las mismas palabras que arma el servidor (CartillaService.unir); sin nada marcado, que no se verá nada.
// La ficha solo cambia al guardar.
(function () {
  var form = document.querySelector('[data-ficha]');
  if (!form) return;
  var casillas = Array.prototype.slice.call(form.querySelectorAll('input[name=registro]'));
  var con = form.querySelector('[data-resumen-con]');
  var vacio = form.querySelector('[data-resumen-vacio]');
  var lista = form.querySelector('[data-resumen-lista]');

  function actualizar() {
    var marcados = casillas.filter(function (c) { return c.checked; })
      .map(function (c) { return c.getAttribute('data-dato'); });
    lista.textContent = marcados.join(', ');
    con.hidden = marcados.length === 0;
    vacio.hidden = marcados.length > 0;
  }

  casillas.forEach(function (c) { c.addEventListener('change', actualizar); });
  actualizar();
})();
