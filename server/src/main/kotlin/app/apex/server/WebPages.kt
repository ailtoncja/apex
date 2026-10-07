package app.apex.server

/** Versão dos Termos de Uso e da Política de Privacidade. Mude quando o texto mudar (o servidor registra qual versão cada pessoa aceitou). */
object Legal {
    const val VERSION = "2026-10-07.2"
}

/** As páginas do site: início, termos, privacidade, exclusão de conta e os links dos e-mails. */
class WebPages(private val config: ServerConfig) {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun shell(title: String, content: String, wide: Boolean = false) = """
        <!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>${esc(title)} — Apex</title>
        <style>
          body{margin:0;background:#0a0a0f;color:#f2f2f7;font-family:Segoe UI,Arial,sans-serif;display:grid;place-items:${if (wide) "start center" else "center"};min-height:100vh}
          main{background:#13131a;border-radius:18px;padding:32px;max-width:${if (wide) 760 else 400}px;width:calc(100% - 48px);margin:${if (wide) "24px 0" else "0"}}
          .logo{font-weight:800;letter-spacing:3px}.logo b{color:#ff3b30} .logo a{color:inherit;text-decoration:none}
          p,li{color:#b4b4c6;line-height:1.6} h1{margin:24px 0 4px} h2{margin:28px 0 6px;font-size:18px} a{color:#ff6b61}
          .muted{color:#7d7d92;font-size:13px} input[type=email],input[type=password]{width:100%;box-sizing:border-box;padding:12px 14px;border-radius:12px;border:1px solid #2c2c3b;
          background:#0a0a0f;color:#fff;font-size:15px;margin:8px 0 14px} button{width:100%;padding:12px;border:0;border-radius:999px;
          background:#ff3b30;color:#fff;font-weight:600;font-size:15px;cursor:pointer} .err{color:#ff6b61}
          .btn{display:inline-block;background:#ff3b30;color:#fff;text-decoration:none;font-weight:600;padding:12px 22px;border-radius:999px}
          nav{margin-top:28px;font-size:13px} nav a{margin-right:16px}
        </style></head><body><main><div class="logo"><a href="/"><b>●</b> APEX</a></div>$content</main></body></html>
    """.trimIndent()

    private val footer = """<nav><a href="/terms">Termos de Uso</a><a href="/privacy">Política de Privacidade</a><a href="/account/delete">Excluir minha conta</a></nav>"""

    private val contact: String
        get() = config.contactEmail?.let { """<a href="mailto:${esc(it)}">${esc(it)}</a>""" } ?: "(o e-mail de contato ainda não foi configurado)"

    private val operator: String
        get() = esc(config.operatorName ?: "o desenvolvedor do projeto")

    fun message(title: String, text: String) = shell(title, "<h2>${esc(title)}</h2><p>${esc(text)}</p>")

    fun resetForm(token: String, error: String? = null) = shell(
        "Nova senha",
        """<h2>Escolha uma nova senha</h2>
           ${if (error != null) "<p class=\"err\">${esc(error)}</p>" else ""}
           <form method="post" action="/reset">
             <input type="hidden" name="token" value="${esc(token)}">
             <input type="password" name="password" placeholder="Nova senha (mínimo 8 caracteres)" minlength="8" maxlength="128" required autofocus>
             <button type="submit">Salvar senha</button>
           </form>""",
    )

    fun deleteForm(error: String? = null) = shell(
        "Excluir conta",
        """<h2>Excluir minha conta</h2>
           <p>Isso apaga a sua conta do Apex e tudo o que está guardado no servidor: inscrições, histórico, listas, curtidas e ajustes.
              Não dá para desfazer. Os dados que ficam só no seu computador não são afetados.</p>
           ${if (error != null) "<p class=\"err\">${esc(error)}</p>" else ""}
           <form method="post" action="/account/delete">
             <input type="email" name="email" placeholder="Seu e-mail" maxlength="254" required autofocus>
             <input type="password" name="password" placeholder="Sua senha" maxlength="128" required>
             <label style="display:block;margin:0 0 14px;color:#b4b4c6"><input type="checkbox" name="confirm" value="yes" required> Entendo que isso é definitivo.</label>
             <button type="submit">Excluir minha conta</button>
           </form>
           <p class="muted">Esqueceu a senha? Use "Esqueci minha senha" no app e depois volte aqui.</p>$footer""",
    )

    fun home() = shell(
        "Vídeos e lives num só lugar",
        """<h1>Vídeos e lives num só lugar</h1>
           <p>O Apex é um aplicativo para Windows para assistir YouTube (sem Shorts), e lives da Twitch e da Kick, com chat, inscrições,
              playlists e histórico. A conta do Apex serve para manter tudo isso igual em todos os seus aparelhos.</p>
           ${config.downloadUrl?.let { """<p><a class="btn" href="${esc(it)}">Baixar o Apex</a></p>""" } ?: ""}
           <p class="muted">O Apex é um projeto independente e não tem ligação com YouTube, Google, Twitch ou Kick.</p>$footer""",
    )

    fun terms() = shell(
        "Termos de Uso",
        """<h1>Termos de Uso</h1><p class="muted">Versão ${Legal.VERSION}</p>
        <h2>1. Aceite</h2>
        <p>Ao criar uma conta ou usar o servidor do Apex, você concorda com estes termos e com a <a href="/privacy">Política de Privacidade</a>.
           Se não concordar, não crie a conta; o app continua funcionando sem ela, só que sem sincronização.</p>
        <h2>2. O que é o Apex</h2>
        <p>O Apex é um aplicativo gratuito e independente, mantido por $operator, para assistir vídeos e lives. O código do aplicativo é aberto, sob a licença GPL-3.0.
           O "servidor do Apex" é o serviço que guarda a sua conta e sincroniza inscrições, histórico, listas, curtidas e ajustes entre os seus aparelhos.</p>
        <h2>3. Plataformas de terceiros</h2>
        <ul>
          <li>O Apex <b>não é afiliado, patrocinado ou aprovado</b> pelo YouTube, Google, Twitch ou Kick. Esses nomes e marcas pertencem aos seus donos.</li>
          <li>O Apex não hospeda, copia nem permite baixar vídeos: o conteúdo vem direto das plataformas para o seu computador e pertence aos seus criadores.</li>
          <li>Ao usar uma conta dessas plataformas pelo Apex, valem também os termos delas. As plataformas podem mudar ou bloquear o acesso a qualquer momento
              e podem aplicar restrições a contas que usam aplicativos não oficiais. Você usa essa parte por sua conta e risco.</li>
          <li>As suas senhas e cookies dessas plataformas ficam só no seu computador. O servidor do Apex nunca os recebe.</li>
        </ul>
        <h2>4. A sua conta</h2>
        <ul>
          <li>Você precisa ter 13 anos ou mais. Quem tem menos de 18 anos deve ter a autorização de um responsável.</li>
          <li>Informe um e-mail que seja seu e escolha uma senha forte. Você é responsável por manter a senha em segredo e pelo que acontece na sua conta.</li>
          <li>Avise-nos se achar que alguém entrou na sua conta.</li>
        </ul>
        <h2>5. Uso aceitável</h2>
        <p>Não é permitido: tentar invadir ou sobrecarregar o servidor; burlar limites de uso; criar contas em massa ou de forma automática;
           usar o serviço para fins ilegais; ou tentar acessar dados de outras pessoas.</p>
        <h2>6. Disponibilidade e mudanças</h2>
        <p>O serviço é gratuito e oferecido "como está", sem promessa de funcionar sem interrupções. Ele pode ficar fora do ar, mudar, ter limites de uso
           (por exemplo, de armazenamento por conta) ou ser encerrado. Quando possível, avisaremos antes e você poderá baixar os seus dados.</p>
        <h2>7. Encerramento</h2>
        <p>Você pode apagar a sua conta quando quiser, no app (Ajustes › Conta do Apex) ou em <a href="/account/delete">esta página</a>.
           Podemos suspender ou apagar contas que violem estes termos.</p>
        <h2>8. Responsabilidade</h2>
        <p>Na medida permitida pela lei, não nos responsabilizamos por perdas indiretas, por falhas ou bloqueios das plataformas de terceiros, nem por dados
           que deixem de ser sincronizados. Nada aqui afasta direitos que a lei garante a você, inclusive os do consumidor.</p>
        <h2>9. Mudanças nestes termos</h2>
        <p>Podemos atualizar estes termos. A versão em vigor e a data aparecem no topo desta página; mudanças importantes serão avisadas no app ou por e-mail.</p>
        <h2>10. Lei aplicável e contato</h2>
        <p>Estes termos seguem as leis do Brasil. Dúvidas, pedidos e denúncias: $contact.</p>$footer""",
        wide = true,
    )

    fun privacy() = shell(
        "Política de Privacidade",
        """<h1>Política de Privacidade</h1><p class="muted">Versão ${Legal.VERSION}</p>
        <p>Explicamos aqui, em português claro, quais dados o Apex guarda, para quê, com quem aparecem e como você controla tudo isso (Lei Geral de Proteção de Dados — LGPD).</p>
        <h2>Em resumo</h2>
        <ul>
          <li>Guardamos só o necessário para a sua conta e para a sincronização funcionarem.</li>
          <li>Não vendemos seus dados, não mostramos anúncios e não usamos rastreadores ou ferramentas de análise.</li>
          <li>Suas senhas e cookies do YouTube, Twitch e Kick ficam só no seu computador (criptografados pelo Windows) e nunca vão para o nosso servidor.</li>
          <li>Se você não criar uma conta do Apex, nada disso é enviado.</li>
          <li>Você pode baixar tudo o que guardamos e apagar a conta quando quiser.</li>
        </ul>
        <h2>Quem é o responsável</h2>
        <p>O controlador dos dados é $operator. Contato para qualquer assunto de privacidade: $contact.</p>
        <h2>Que dados guardamos e para quê</h2>
        <ul>
          <li><b>Conta:</b> e-mail, nome de exibição, senha (guardada apenas como um código irreversível, "hash" Argon2id), se o e-mail foi confirmado, data de criação
              e a data e a versão do aceite dos termos. Serve para criar e proteger a sua conta. Base legal: execução do contrato (você pediu a conta).</li>
          <li><b>Sessões:</b> um código (também guardado só como hash), o nome do aparelho/programa (por exemplo "Apex Windows") e as datas de início e validade.
              Serve para manter você conectado e encerrar sessões em caso de suspeita de roubo.</li>
          <li><b>Dados sincronizados:</b> canais em que você se inscreveu no Apex, histórico (até 500 vídeos), "assistir mais tarde", curtidas e descurtidas marcadas,
              playlists e ajustes (qualidade, idioma, canais bloqueados). Serve para deixar tudo igual nos seus aparelhos. Base legal: execução do contrato.</li>
          <li><b>Dados técnicos:</b> endereço IP, horário e endereço acessado, nos registros da hospedagem e do servidor, e contadores temporários na memória que limitam
              abusos. Serve para segurança e estabilidade. Base legal: legítimo interesse.</li>
          <li><b>E-mails:</b> enviamos apenas mensagens do serviço (confirmar e-mail e redefinir senha). Não enviamos propaganda.</li>
        </ul>
        <h2>O que o app faz no seu computador</h2>
        <p>O Apex se conecta direto ao YouTube, à Twitch e à Kick a partir do seu computador. Essas plataformas veem o seu endereço IP e, se você entrou numa conta delas, as
           ações dessa conta (por exemplo curtir ou se inscrever); valem as políticas de privacidade delas. O servidor do Apex não participa dessa comunicação e não vê o que você assiste,
           exceto o histórico que você escolher sincronizar.</p>
        <p>O app também consulta o <b>GitHub</b> para saber se saiu uma versão nova e baixa a atualização de lá; o GitHub vê o seu endereço IP, como em qualquer download.
           As atualizações só são instaladas se estiverem assinadas digitalmente pelo projeto.</p>
        <h2>Com quem os dados passam</h2>
        <p>Usamos serviços de terceiros para operar o Apex, que tratam os dados apenas para isso:</p>
        <ul>
          <li><b>Render</b> (hospedagem do servidor, Estados Unidos);</li>
          <li><b>Neon</b> (banco de dados PostgreSQL, região de São Paulo);</li>
          <li><b>Resend</b> (envio dos e-mails do serviço, Estados Unidos), que recebe o seu e-mail e o texto da mensagem.</li>
        </ul>
        <p>Por isso, parte do tratamento ocorre fora do Brasil, com os cuidados que a LGPD exige. Só compartilhamos dados com autoridades se a lei obrigar.</p>
        <h2>Por quanto tempo guardamos</h2>
        <ul>
          <li>Os dados da conta e os sincronizados ficam enquanto a conta existir. Ao apagar a conta, tudo é removido do banco na hora.</li>
          <li>Cópias de segurança do provedor do banco podem existir por um curto período depois disso.</li>
          <li>Sessões vencidas e links de e-mail usados são apagados automaticamente. A marca de "item apagado" (sem o conteúdo) fica por até ${Maintenance.TOMBSTONE_DAYS} dias,
              para os seus outros aparelhos ficarem sabendo.</li>
          <li>Os registros técnicos seguem o prazo da hospedagem.</li>
        </ul>
        <h2>Seus direitos</h2>
        <p>Pela LGPD você pode pedir: confirmação de que tratamos seus dados; acesso e cópia; correção; anonimização, bloqueio ou eliminação; portabilidade; informações sobre
           com quem compartilhamos; e revogar o consentimento. Na prática:</p>
        <ul>
          <li><b>Baixar tudo:</b> no app, em Ajustes › Conta do Apex › "Baixar meus dados" (arquivo JSON).</li>
          <li><b>Apagar a conta:</b> no app ou em <a href="/account/delete">esta página</a>.</li>
          <li><b>Qualquer outro pedido:</b> escreva para $contact. Você também pode reclamar à Autoridade Nacional de Proteção de Dados (ANPD).</li>
        </ul>
        <h2>Segurança</h2>
        <p>Usamos conexão criptografada (HTTPS), senhas guardadas só como hash, sessões que se renovam e se encerram sozinhas em caso de uso suspeito, e limites contra tentativas
           de invasão. Nenhum sistema é 100% seguro; se houver um incidente que afete você, avisaremos como a lei determina.</p>
        <h2>Crianças</h2>
        <p>O Apex não é destinado a menores de 13 anos e não coletamos dados de crianças de propósito. Se você acha que uma criança criou uma conta, avise-nos para apagá-la.</p>
        <h2>Mudanças</h2>
        <p>Se esta política mudar, a nova versão e a data aparecem no topo desta página, e mudanças importantes serão avisadas no app ou por e-mail.</p>$footer""",
        wide = true,
    )
}
