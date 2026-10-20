/**
 * Relay de e-mail do Apex no Google Apps Script (grátis, sem domínio): recebe o pedido do servidor e envia pelo seu Gmail.
 * Passo a passo em docs/email-relay/README.md.
 *
 * Limite do Google: 100 destinatários por dia em conta Gmail comum (1.500 em Google Workspace).
 */

function doPost(e) {
  try {
    var secret = PropertiesService.getScriptProperties().getProperty('SECRET');
    var data = JSON.parse(e.postData.contents);

    // Só o servidor do Apex conhece a senha. Sem isso, qualquer pessoa poderia mandar e-mail em seu nome.
    if (!secret || !data.secret || !same(String(data.secret), String(secret))) return reply({ ok: false, error: 'forbidden' });

    var to = String(data.to || '').trim();
    var subject = String(data.subject || '').replace(/[\r\n]+/g, ' ').substring(0, 200);
    // Um único destinatário, e nada de lista.
    if (!/^[^\s@,;<>]+@[^\s@,;<>]+\.[^\s@,;<>]{2,}$/.test(to) || !subject) return reply({ ok: false, error: 'invalid' });

    MailApp.sendEmail({
      to: to,
      subject: subject,
      body: String(data.text || ''),
      htmlBody: String(data.html || ''),
      name: 'Apex',
    });
    return reply({ ok: true });
  } catch (err) {
    return reply({ ok: false, error: 'failed' });
  }
}

/**
 * Compara as senhas em tempo constante: o `!==` para no primeiro caractere diferente, e medir o tempo da resposta
 * entregaria a senha aos poucos. Percorre sempre o tamanho todo da senha certa.
 */
function same(a, b) {
  var diff = a.length ^ b.length;
  for (var i = 0; i < b.length; i++) {
    diff |= (i < a.length ? a.charCodeAt(i) : 0) ^ b.charCodeAt(i);
  }
  return diff === 0;
}

function reply(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}
