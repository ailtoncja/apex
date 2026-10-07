# Contas e sincronização do Apex

Decisões: **app público**, **servidor próprio** (Kotlin/Ktor) com **PostgreSQL** (Neon em produção), **OAuth oficial** das
plataformas na fase 2. O Supabase foi descartado porque o plano grátis limita a 2 projetos.

## O que já existe e está testado

| Parte | Estado |
|---|---|
| Servidor: cadastro (com aceite dos termos), login, renovação de sessão, sair, esqueci a senha, confirmar e-mail, apagar conta (no app e pelo site) | pronto, 30 testes contra um PostgreSQL de verdade |
| Segurança: senhas Argon2id, tokens de sessão girados a cada uso com detecção de roubo, limite de tentativas por IP e por e-mail, respostas que não revelam quem tem conta, cabeçalhos de segurança, tamanho máximo de pedido, logs sem os tokens dos links | pronto |
| Proteção do banco grátis: teto de contas, limite de contas novas e de pedidos de senha por IP, cota de dados por conta, limpeza automática de sessões vencidas e itens apagados antigos | pronto |
| LGPD: Termos de Uso e Política de Privacidade (`/terms`, `/privacy`), baixar todos os dados (`GET /v1/me/export`), apagar a conta (app e `/account/delete`) | pronto |
| API de sincronização (`POST /v1/sync`): só o que mudou, funciona offline, conflito vence a edição mais recente, apagar chega aos outros aparelhos | pronto |
| App: tela "Conta do Apex" (entrar, criar com aceite dos termos, sair, apagar, baixar meus dados, esqueci a senha, sincronizar agora) e motor de sincronização automática | pronto, 8 testes de ponta a ponta com dois aparelhos simulados |
| Ligar YouTube, Twitch e Kick por OAuth oficial e sincronizar com elas | **fase 2, ainda não começou** |
| "Entrar com Google/Twitch" como login do Apex | fase 2 |

## Como rodar no seu PC

```bash
Servidor.cmd        # sobe o servidor em http://127.0.0.1:8080 com um PostgreSQL embutido (dados em %USERPROFILE%\.apex-server)
Apex.cmd            # abre o app; em Ajustes > Conta do Apex, crie uma conta
```

Com o servidor rodando, os e-mails de confirmar e redefinir senha aparecem **no log do servidor** (não há provedor de
e-mail em desenvolvimento). Para testar dois aparelhos no mesmo PC, abra o app com `APEX_DATA` apontando para pastas diferentes.

## Como publicar (para outras pessoas usarem)

1. **Banco:** crie um projeto grátis no [Neon](https://neon.tech) e copie a *connection string*.
2. **Servidor:** hospede o `Dockerfile` da raiz num serviço de containers (Render, Fly.io, Railway, Koyeb ou uma VPS).
   Defina as variáveis de [`server/.env.example`](../server/.env.example): `DATABASE_URL`, `JWT_SECRET`, `PUBLIC_URL`,
   `TRUST_PROXY=true` e, para e-mails de verdade, `RESEND_API_KEY` e `MAIL_FROM` (conta grátis no [Resend](https://resend.com)).
   As tabelas são criadas sozinhas na primeira subida (Flyway).
3. **App:** o servidor padrão (`DEFAULT_SERVER_URL` em `composeApp/src/commonMain/kotlin/app/apex/cloud/CloudClient.kt`) já aponta
   para o servidor público no Render (`https://apex-server-mg5l.onrender.com`). Para testar com o servidor do seu PC, mude em
   *Conta do Apex › Servidor* para `http://localhost:8080`.

O `Dockerfile` ainda não foi testado (não há Docker neste PC); o servidor em si foi testado rodando direto na JVM.

## Como funciona a sincronização

- O app compara o conteúdo de cada item (inscrição, vídeo do histórico, playlist, curtida, ajuste) com o que já sabia dele
  e anota na hora o que mudou, com a hora da mudança. Itens apagados viram "lápides".
- Cerca de 2,5 segundos depois de uma mudança, o app envia só o que mudou e recebe o que mudou nos outros aparelhos
  (`since` = cursor devolvido pelo servidor, que volta 5 segundos de propósito para nunca perder uma gravação fora de ordem).
- **Conflito no mesmo item:** vale a edição mais recente (relógio do aparelho, limitado a 5 minutos no futuro).
- **Primeira vez num aparelho:** o app primeiro traz o que a conta já tem, depois envia o que só existe ali. Assim, entrar
  num PC novo não sobrescreve os seus ajustes.
- **Trocar de conta no mesmo aparelho:** o app pergunta se quer juntar os dados ou começar do zero, para um não vazar para o outro.
- Ajustes que dependem do aparelho (volume, menu lateral, decodificação por hardware, histórico de buscas) **não** sincronizam.

## O que dá para sincronizar com cada plataforma (fase 2)

| | Ler inscrições/seguidos | Seguir/inscrever pelo Apex | Curtir | Playlists | Histórico | Chat (escrever) |
|---|---|---|---|---|---|---|
| **YouTube** (API oficial) | sim | sim | sim | sim | **não** (a API não expõe) | sim |
| **Twitch** (Helix) | sim | **não** (a Twitch removeu da API) | — | — | — | sim |
| **Kick** (API pública) | a confirmar | a confirmar | — | — | — | sim |

- **YouTube:** nos dois sentidos para inscrições, curtidas e playlists. Histórico, "assistir depois" e recomendações da conta
  continuam só pelo login no aparelho.
- **Twitch:** o Apex importa e mantém atualizado quem você segue. Seguir dentro do Apex fica só no Apex.
- **Kick:** vamos confirmar na API oficial o que existe antes de prometer.

## Antes de abrir para o público (checklist)

O passo a passo do que falta fazer está em [PUBLICAR.md](PUBLICAR.md). Resumo das decisões:

1. **Licença (decidido: GPL-3.0).** O player usa a biblioteca vlcj, que é **GPL-3.0**; por isso o Apex inteiro é GPL-3.0 (como o NewPipe)
   e quem receber o programa tem direito ao código. Ver [LICENSE](../LICENSE) e [NOTICE.md](../NOTICE.md). Para voltar a ter código fechado,
   seria preciso trocar o vlcj por uma ligação direta com o libVLC (LGPL).
2. **Termos do YouTube.** O Apex toca vídeos com yt-dlp e a API interna do YouTube, que o YouTube não autoriza. Distribuir
   publicamente aumenta o risco de notificação ou bloqueio. A ligação de contas deve ser sempre opcional.
3. **Google/YouTube OAuth.** O escopo do YouTube é sensível: exige verificação do app (vídeo, política de privacidade, domínio)
   e, até lá, limita a 100 usuários de teste. A cota padrão da API é de 10.000 unidades por dia para o projeto todo.
4. **Política de privacidade e termos de uso** (LGPD): **feitos** e publicados pelo servidor em `/privacy` e `/terms`. São textos em
   linguagem simples, escritos sem revisão de advogado; vale revisar quando o app crescer. Defina `CONTACT_EMAIL` e `OPERATOR_NAME` no servidor.
5. **Instalador e atualização.** Hoje o app é um `.jar` que exige Java e VLC instalados; falta um pacote com o Java dentro
   (precisa de um JDK com `jpackage`) e assinatura de código para o Windows não alertar.
6. **Operação:** backups do banco (o Neon faz), monitoramento e um canal de suporte.
7. **Custos:** Neon e Resend têm plano grátis; a hospedagem do servidor costuma custar de US$ 0 (com limites) a ~US$ 5 por mês.
