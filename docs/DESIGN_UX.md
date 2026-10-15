# Design e UX

## Navegação

```text
Primeiro uso: Descoberta/IP → seleção → autorização na TV → Controle

Uso diário
├── Controle: energia, D-pad, Home/Voltar, volume/mute
├── Fila: reprodução atual, próximas pesquisas, remoção e reordenação
├── Apps: aplicativos/favoritos e entradas reais
├── Touchpad: cursor, rolagem e teclado contextual
└── Configurações: conexão, YouTube, imagem, captura e preferências
```

## Decisões

- **Tempo até o primeiro valor:** a configuração pede apenas a mesma rede e leva diretamente ao primeiro comando; não há conta, perfil ou tour.
- **Estado vazio com próxima ação:** descoberta e IP manual ficam na mesma tela. Listas vazias explicam que a TV não anunciou itens.
- **Hierarquia:** os comandos diários ocupam a primeira tela. Imagem, uso e ajustes ficam em `Mais`.
- **Defaults úteis:** reconexão e monitoramento em primeiro plano são automáticos; monitoramento 24h fica desativado e não foi escondido em um serviço.
- **Dados honestos:** o painel informa “observado” e não converte lacunas em zero ou estimativa.
- **Confirmação proporcional:** desligar e esquecer pedem confirmação; volume e navegação não interrompem o fluxo.

Essas decisões adaptam os módulos `onboarding-and-activation`, `product-strategy-and-features`, `dashboards-and-data-viz` e `revenue-centric-design` da skill de Richard/@richardrx curada por `heliocosta-dev`. Princípios comerciais, monetização e retenção artificial foram deliberadamente excluídos.

## Estados verificáveis

| Estado | Tratamento |
|---|---|
| Sem configuração | ação principal de descoberta + IP manual |
| Descobrindo | progresso não bloqueante e botão temporariamente desabilitado |
| Aguardando TV | instrução para confirmar na tela do televisor |
| Conectado | indicador verde e comandos habilitados |
| Offline/reconectando | causa curta, tentativa manual e três tentativas automáticas com backoff |
| Recurso incompatível | controle oculto/desabilitado com motivo |
| Sem dados de uso | texto explícito; nenhuma hora fictícia |
| Comando rejeitado | snackbar recuperável com mensagem da TV |

## Checklist de acessibilidade

- alvos essenciais com pelo menos 48 dp;
- descrições semânticas para D-pad, energia, touchpad, rolagem e ações por ícone;
- estado não depende só de cor: existe texto de conexão;
- tipografia usa escalonamento nativo do Android;
- contraste escuro Material 3 e áreas seguras para barras/IME;
- uso com uma mão deve ser validado no celular real antes de uma versão de produção.
