// Relay de e-mail do Apex na Vercel (grátis, sem domínio): recebe o pedido do servidor e envia pelo Gmail (SMTP, senha de app).
// Passo a passo em docs/email-relay/README.md. Limite do Gmail: cerca de 500 e-mails por dia.
import nodemailer from 'nodemailer';
import { timingSafeEqual } from 'node:crypto';

function same(a, b) {
  const x = Buffer.from(String(a ?? ''));
  const y = Buffer.from(String(b ?? ''));
  return x.length === y.length && timingSafeEqual(x, y);
}

export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ ok: false, error: 'method' });

  const { secret, to, subject, html, text } = req.body ?? {};
  // Só o servidor do Apex conhece a senha. Sem isso, qualquer pessoa poderia mandar e-mail em seu nome.
  if (!process.env.RELAY_SECRET || !same(secret, process.env.RELAY_SECRET)) {
    return res.status(403).json({ ok: false, error: 'forbidden' });
  }

  const destination = String(to ?? '').trim();
  const title = String(subject ?? '').replace(/[\r\n]+/g, ' ').slice(0, 200);
  // Um único destinatário, e nada de lista.
  if (!/^[^\s@,;<>]+@[^\s@,;<>]+\.[^\s@,;<>]{2,}$/.test(destination) || !title) {
    return res.status(400).json({ ok: false, error: 'invalid' });
  }

  try {
    const transport = nodemailer.createTransport({
      host: 'smtp.gmail.com',
      port: 465,
      secure: true,
      auth: { user: process.env.GMAIL_USER, pass: process.env.GMAIL_APP_PASSWORD },
    });
    await transport.sendMail({
      from: `Apex <${process.env.GMAIL_USER}>`,
      to: destination,
      subject: title,
      text: String(text ?? ''),
      html: String(html ?? ''),
    });
    return res.status(200).json({ ok: true });
  } catch {
    return res.status(502).json({ ok: false, error: 'send_failed' });
  }
}
