# Matriz de compatibilidade

Compatibilidade confirmada exige teste em uma TV real; compilar o aplicativo ou executar um emulador não valida o firmware.

## TV validada

| Item | Resultado real |
|---|---|
| Modelo | LG 43UP7500PSF |
| Plataforma | webOS TV 6.x, geração 2021 |
| Firmware | 03.53.45 |
| SSDP e IP manual | funcionando |
| WSS 3001 / pareamento | funcionando |
| Persistência da autorização | funcionando |
| Pointer, D-pad, Home e Voltar | funcionando |
| Volume e mute | funcionando |
| Apps e entradas HDMI | funcionando |
| Touchpad | funcionando |
| Wake-on-LAN | funcionando com a TV configurada |
| Captura da tela | funcionando |
| Pesquisa direta no YouTube | funcionando no app instalado durante o teste |
| Fila por duração estimada | funcionando; não representa o estado real do player |
| Leitura/escrita de imagem | parcial e dependente do parâmetro |

## Expectativa por plataforma

| Plataforma | Expectativa | Confiança |
|---|---|---|
| webOS TV 6.x | mesma família da TV testada | alta |
| webOS TV 22–26 | controle básico e descoberta provavelmente compatíveis | alta, não testada |
| webOS TV 4.x–5.x | SSAP provavelmente compatível; recursos recentes podem variar | média, não testada |
| webOS TV 3.x | controle básico pode funcionar | baixa, não testada |
| webOS TV 1.x–2.x | transporte e permissões podem ser incompatíveis | desconhecida |

A nomenclatura oficial mudou de `6.x` para anos (`22`, `23`, ...). Consulte a [tabela oficial da LG](https://webostv.developer.lge.com/develop/specifications/web-api-and-web-engine).

## Como relatar outro modelo

Inclua modelo, versão do webOS, firmware e resultado de cada recurso. Remova IP, MAC, credenciais ADB e `client-key` antes de publicar logs ou capturas.

Se o certificado da TV mudar legitimamente após atualização ou restauração, use **Configurações → Esquecer esta TV** e pareie novamente. Uma troca silenciosa é bloqueada.
