# E-mail grátis sem domínio (relay)

O Resend só envia para qualquer pessoa com um domínio verificado, e o Render grátis bloqueia as portas de e-mail (SMTP). A saída gratuita:
um **relay**, um pequeno endereço HTTPS seu que recebe o pedido do servidor do Apex e envia o e-mail **pela sua conta do Gmail**.
O servidor já sabe falar com ele (variáveis `MAIL_WEBHOOK_URL` e `MAIL_WEBHOOK_SECRET`).

| | Google Apps Script (recomendado) | Vercel |
|---|---|---|
| Custo | grátis | grátis (plano Hobby, só para uso não comercial) |
| Limite por dia | 100 destinatários (Gmail comum) | cerca de 500 (limite do Gmail) |
| O que precisa | só a conta Google | conta Vercel + verificação em duas etapas + "senha de app" do Gmail |
| Dificuldade | colar um script e implantar | importar uma pasta do projeto |

Em qualquer um, o e-mail sai **do seu Gmail** (o destinatário vê o seu endereço como remetente) e as respostas chegam a você.
Se um dia tiver domínio, basta preencher `RESEND_API_KEY` no Render: ele tem prioridade sobre o relay.

**Cuidado com a senha do relay** (`SECRET`): quem a souber consegue mandar e-mails em seu nome. Ela só vai no painel do Render e no relay, nunca no GitHub.
Gere uma longa e aleatória, por exemplo no PowerShell: `[guid]::NewGuid().ToString("N") + [guid]::NewGuid().ToString("N")`.

---

## Opção A: Google Apps Script

1. Entre em <https://script.google.com> com o Gmail que vai enviar e clique em **Novo projeto**.
2. Apague o código que vem pronto e cole o conteúdo de [`apps-script.gs`](apps-script.gs). Dê um nome ao projeto (por exemplo "Apex e-mail").
3. Engrenagem **Configurações do projeto › Propriedades do script › Adicionar propriedade**: nome `SECRET`, valor = a senha longa que você gerou. Salve.
4. **Implantar › Nova implantação**, tipo **App da Web**:
   - *Executar como*: **Eu**;
   - *Quem pode acessar*: **Qualquer pessoa**.

   Clique em **Implantar** e autorize quando o Google pedir. Como o script é seu, aparece "O Google não verificou este app": clique em *Avançado* e depois em *Acessar … (não seguro)*, e permita o envio de e-mails.
5. Copie o **URL do app da Web** (termina em `/exec`).
6. No Render (*apex-server › Environment*): `MAIL_WEBHOOK_URL` = esse URL e `MAIL_WEBHOOK_SECRET` = a mesma senha. Salve (o servidor reinicia).

Se mudar o script depois, é preciso **Implantar › Gerenciar implantações › Editar › Nova versão** para a mudança valer.

## Opção B: Vercel

1. No Gmail ligue a **verificação em duas etapas** e crie uma **senha de app** em <https://myaccount.google.com/apppasswords> (16 letras).
2. Em <https://vercel.com> crie o projeto: **Add New › Project › Import Git Repository** (`ailtoncja/apex`), com **Root Directory** = `docs/email-relay/vercel`.
   Em *Environment Variables* adicione `RELAY_SECRET` (a senha longa), `GMAIL_USER` (seu Gmail) e `GMAIL_APP_PASSWORD` (a senha de app). Clique em **Deploy**.
3. O endereço do relay é `https://NOME-DO-PROJETO.vercel.app/api/send`. Se ao testar vier uma página de login da Vercel, desligue *Vercel Authentication* em
   **Settings › Deployment Protection** do projeto.
4. No Render: `MAIL_WEBHOOK_URL` = esse endereço e `MAIL_WEBHOOK_SECRET` = o `RELAY_SECRET`. Salve.

## Conferir

- Abra `https://apex-server-mg5l.onrender.com/v1/info`: deve mostrar `"mailEnabled":true`.
- No app, em Ajustes › Conta do Apex, use **Esqueci minha senha** com um e-mail seu e olhe também a caixa de spam.
- Se não chegar, veja o log do servidor no Render: uma linha "Falha ao enviar e-mail" diz se o relay recusou (senha errada, cota do dia, etc.).
