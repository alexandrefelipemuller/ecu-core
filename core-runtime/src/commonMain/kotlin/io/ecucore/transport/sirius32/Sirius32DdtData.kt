// GERADO por ecu-core/tools/gen_sirius32_ddt.py - NÃO editar à mão.
// Fonte: perfil DDT2000 'SIRIUS32 - EMS3132 - Soft RE0_A30' (autoident [{"diagversion": "19", "supplier": "001", "version": "A300", "soft": "00E0"}]).
// Referência a validar em carro: NÃO é captura real da central.
package io.ecucore.transport.sirius32

internal object Sirius32DdtData {
    const val ECU_NAME = "SIRIUS32 - EMS3132 - Soft RE0_A30"

    private const val F80 = """
N° de  calibration mise au point	23	0	8	0	1	0	1		
N° version du logiciel	19	0	16	0	1	0	1		
N° programme	17	0	16	0	1	0	1		
N° RENAULT du calculateur	3	0	40	0	1	0	1		
N° hardware	12	0	40	0	1	0	1		
N° fournisseur	9	0	24	0	1	0	1		
N° de calibration systeme	24	0	8	0	1	0	1		
N° diag	8	0	8	0	1	0	1		
N° calibration	21	0	16	0	1	0	1		
"""
    private const val FA0 = """
Panne mémorisée sur la liaison ABS	6	7	1	0	1	0	1		0=OK¦1=Panne
Panne présente liaison gpl-inj	7	6	1	0	1	0	1		0=OK¦1=Panne
FLGPPE2	5	0	8	0	1	0	1		
Panne mémorisée du capteur vitesse véhicule	6	0	1	0	1	0	1		0=OK¦1=Panne
Panne présente sonde à oxygène amont	7	0	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée sur alimentation +APC	4	0	1	0	1	0	1		0=OK¦1=Panne
Panne présente sonde à oxygène aval	7	1	1	0	1	0	1		0=OK¦1=Panne
Panne présente observation évolution de pression	7	5	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée du potentiomètre EGR	8	2	1	0	1	0	1		0=OK¦1=Panne
Initialisation de la mémoire de sauvegarde effectuée	9	0	1	0	1	0	1		0=non effectuée¦1=EFFECTUEE
FLGPME2	6	0	8	0	1	0	1		
FLGPME3	8	0	8	0	1	0	1		
Panne présente sur alimentation +APC	3	0	1	0	1	0	1		0=OK¦1=Panne
Panne présente du capteur de cliquetis	3	6	1	0	1	0	1		0=OK¦1=Panne
Panne zone verrou logiciel de la sauvegarde	9	3	1	0	1	0	1		0=OK¦1=Panne
Panne CKS global de la mémoire de sauvegarde	9	1	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée observation évolution de pression	8	5	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée sonde à oxygène amont	8	0	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée du topeur arbre à cames	4	7	1	0	1	0	1		0=OK¦1=Panne
ETAMEM1	9	0	8	0	1	0	1		
Panne mémorisée du capteur de cliquetis	4	6	1	0	1	0	1		0=OK¦1=Panne
Panne présente sur la liaison clim - injection	5	5	1	0	1	0	1		0=OK¦1=Panne
Panne présente sur la liaison codée	5	4	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée du capteur de pression absolue collecteur	4	2	1	0	1	0	1		0=OK¦1=Panne
Panne présente du capteur de pression absolue collecteur	3	2	1	0	1	0	1		0=OK¦1=Panne
FLGPME1	4	0	8	0	1	0	1		
Panne présente pression climatisation	7	3	1	0	1	0	1		0=OK¦1=Panne
Panne présente défault cible volant moteur	5	2	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée pression climatiation	8	3	1	0	1	0	1		0=OK¦1=Panne
Panne présente sur la liaison BVA	5	6	1	0	1	0	1		0=OK¦1=Panne
Panne  présente absence de signal dent	5	1	1	0	1	0	1		0=OK¦1=Panne
Panne présente du topeur  arbre à cames	3	7	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée sur la liaison codée	6	4	1	0	1	0	1		0=OK¦1=Panne
Panne présente acquisition masse moteur avec sonde type 1 fil	7	4	1	0	1	0	1		0=OK¦1=Panne
Panne  mémorisée absence de signal dent	6	1	1	0	1	0	1		0=OK¦1=Panne
Panne présente du potentiomètre EGR	7	2	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée du potentiomètre papillon	4	3	1	0	1	0	1		0=OK¦1=Panne
Panne présente du capteur de température d eau	3	4	1	0	1	0	1		0=OK¦1=Panne
Panne présente du potentiomètre papillon	3	3	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée du capteur de température d air	4	5	1	0	1	0	1		0=OK¦1=Panne
Panne présente sur alimentation VBR	3	1	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée sur la liaison BVA	6	6	1	0	1	0	1		0=OK¦1=Panne
Panne calculateur injection	9	2	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée du capteur de température d eau	4	4	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée liaison gpl-inj	8	6	1	0	1	0	1		0=OK¦1=Panne
FLGPPE1	3	0	8	0	1	0	1		
FLGPPE3	7	0	8	0	1	0	1		
Panne mémorisée sur alimentation VBR	4	1	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée acquisition masse moteur avec sonde type 1 fil	8	4	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée Borne DF alternateur	8	7	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée sur la liaison clim - injection	6	5	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée sonde à oxygène aval	8	1	1	0	1	0	1		0=OK¦1=Panne
Panne présente du capteur de vitesse véhicule	5	0	1	0	1	0	1		0=OK¦1=Panne
Panne présente du capteur de tempéature d air	3	5	1	0	1	0	1		0=OK¦1=Panne
Panne présente sur la liaison ABS	5	7	1	0	1	0	1		0=OK¦1=Panne
Panne présente Borne DF alternateur	7	7	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée défault cible volant moteur	6	2	1	0	1	0	1		0=OK¦1=Panne
"""
    private const val FA1 = """
FLGSCM1	4	0	8	0	1	0	1		
Sortie commande CTPH en circuit ouvert	20	6	1	0	1	0	1		0=OK¦1=Panne
Sortie relais actuateurs en CC à la masse	9	0	1	0	1	0	1		0=OK¦1=Panne
Panne présente voyant MIL	16	7	1	0	1	0	1		0=OK¦1=Panne
Sortie voyant défaut en circuit ouvert	10	6	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée chauffage sonde Aval	17	5	1	0	1	0	1		0=OK¦1=Panne
Sortie relais gmv2 en circuit ouvert	10	4	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée relais de pompe à air	12	2	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée voyant alerte température eau	12	7	1	0	1	0	1		0=OK¦1=Panne
Injecteur 3 en CC à la masse	4	2	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée voyant MIL	17	7	1	0	1	0	1		0=OK¦1=Panne
Panne présente chauffage sonde Amont	16	3	1	0	1	0	1		0=OK¦1=Panne
Sortie relais pompe à essence en circuit ouvert	10	1	1	0	1	0	1		0=OK¦1=Panne
Injecteur 2 en CC à la masse	4	1	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée sorties allumage 2 et 3 .	7	5	1	0	1	0	1		0=OK¦1=Panne
Sortie chauffage sonde amont en circuit ouvert	15	3	1	0	1	0	1		0=OK¦1=Panne
Sortie chauffage sonde amont en CC au +bat	13	3	1	0	1	0	1		0=OK¦1=Panne
Injecteur 1 en CC au +bat	3	0	1	0	1	0	1		0=OK¦1=Panne
Sortie commande CTPH en court-circuit au +bat	18	6	1	0	1	0	1		0=OK¦1=Panne
Sortie voyant alerte eau en circuit ouvert	10	7	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée sortie décaleur arbre à came	22	7	1	0	1	0	1		0=OK¦1=Panne
Sorties allumage 2 et 3 en CC au +bat.	3	5	1	0	1	0	1		0=OK¦1=Panne
Sortie décaleur arbre à came en CC à la masse	19	7	1	0	1	0	1		0=OK¦1=Panne
Panne présente commande CTPH	21	6	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée relais de pompe à essence	12	1	1	0	1	0	1		0=OK¦1=Panne
FLGPMS4	22	0	8	0	1	0	1		
FLGPMS2	12	0	8	0	1	0	1		
FLGPMS3	17	0	8	0	1	0	1		
FLGPMS1	7	0	8	0	1	0	1		
Sortie EGR en CC à la masse	14	0	1	0	1	0	1		0=OK¦1=Panne
Panne présente voyant défaut	11	6	1	0	1	0	1		0=OK¦1=Panne
Sorties allumage 2 et 3 en circuit ouvert	5	5	1	0	1	0	1		0=OK¦1=Panne
Sortie EGR en circuit ouvert	15	0	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée blocage mécanique EGR	17	1	1	0	1	0	1		0=OK¦1=Panne
Sortie relais pompe à essence en CC à la masse	9	1	1	0	1	0	1		0=OK¦1=Panne
Injecteur 2 en CC au +bat	3	1	1	0	1	0	1		0=OK¦1=Panne
Sortie voyant défaut en CC à la masse	9	6	1	0	1	0	1		0=OK¦1=Panne
Panne présente purge Canister	16	2	1	0	1	0	1		0=OK¦1=Panne
Sorties allumage 2 et 3 en CC à la masse.	4	5	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée puissance chauffage O2 Amont	17	4	1	0	1	0	1		0=OK¦1=Panne
Panne présente relais actuateur	11	0	1	0	1	0	1		0=OK¦1=Panne
Sortie régulation ralenti en CC à la masse	19	0	1	0	1	0	1		0=OK¦1=Panne
Sortie EGR en CC au +bat	13	0	1	0	1	0	1		0=OK¦1=Panne
Sortie climatisation en CC au +bat	8	5	1	0	1	0	1		0=OK¦1=Panne
Injecteur 3 en CC au +bat	3	2	1	0	1	0	1		0=OK¦1=Panne
Sortie voyant MIL en circuit ouvert	15	7	1	0	1	0	1		0=OK¦1=Panne
Panne présente puissance chauffage O2 Amont	16	4	1	0	1	0	1		0=OK¦1=Panne
Sortie purge canister en CC à la masse	14	2	1	0	1	0	1		0=OK¦1=Panne
Sortie décaleur arbre à came en circuit ouvert	20	7	1	0	1	0	1		0=OK¦1=Panne
FLGSCP1	3	0	8	0	1	0	1		
FLGSCP2	8	0	8	0	1	0	1		
FLGSCP3	13	0	8	0	1	0	1		
FLGSCP4	18	0	8	0	1	0	1		
Injecteur 4 en CC à la masse	4	3	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée chauffage sonde Amont	17	3	1	0	1	0	1		0=OK¦1=Panne
Panne présente Injecteur 3	6	2	1	0	1	0	1		0=OK¦1=Panne
Panne présente Injecteur 2	6	1	1	0	1	0	1		0=OK¦1=Panne
Panne présente Injecteur 1	6	0	1	0	1	0	1		0=OK¦1=Panne
Panne présente voyant alerte température eau	11	7	1	0	1	0	1		0=OK¦1=Panne
Panne présente Injecteur 4	6	3	1	0	1	0	1		0=OK¦1=Panne
Panne présente relais de pompe à air	11	2	1	0	1	0	1		0=OK¦1=Panne
Panne présente puissance chauffage O2 Aval	16	6	1	0	1	0	1		0=OK¦1=Panne
Sortie relais pompe à air en CC à la masse	9	2	1	0	1	0	1		0=OK¦1=Panne
Sortie climatisation en circuit ouvert	10	5	1	0	1	0	1		0=OK¦1=Panne
Panne présente sortie EGR	16	0	1	0	1	0	1		0=OK¦1=Panne
Sortie voyant MIL en CC à la masse	14	7	1	0	1	0	1		0=OK¦1=Panne
Sortie relais gmv2 en CC au +bat	8	4	1	0	1	0	1		0=OK¦1=Panne
Panne présente sorties allumage 2 et 3.	6	5	1	0	1	0	1		0=OK¦1=Panne
Sorties allumage 1 et 4 en circuit ouvert	5	4	1	0	1	0	1		0=OK¦1=Panne
Sortie voyant défaut en CC au +bat	8	6	1	0	1	0	1		0=OK¦1=Panne
Sortie relais pompe à air en circuit ouvert	10	2	1	0	1	0	1		0=OK¦1=Panne
Sortie voyant MIL en CC au +bat	13	7	1	0	1	0	1		0=OK¦1=Panne
Panne présente sortie décaleur arbre à came	21	7	1	0	1	0	1		0=OK¦1=Panne.
Panne mémorisée purge Canister	17	2	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée relais GMV 1	12	3	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée relais GMV 2	12	4	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée Injecteur 4	7	3	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée Injecteur 2	7	1	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée Injecteur 3	7	2	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée Injecteur 1	7	0	1	0	1	0	1		0=OK¦1=Panne
Sorties allumage 1 et 4 en CC au +bat.	3	4	1	0	1	0	1		0=OK¦1=Panne
Sortie chauffage sonde aval en CC à la masse	14	5	1	0	1	0	1		0=OK¦1=Panne
Sortie décaleur arbre à came en CC au +bat	18	7	1	0	1	0	1		0=OK¦1=Panne
Panne présente Climatisation	11	5	1	0	1	0	1		0=OK¦1=Panne
Sortie relais pompe à air en CC au +bat	8	2	1	0	1	0	1		0=OK¦1=Panne
Sortie voyant alerte eau en CC à la masse	9	7	1	0	1	0	1		0=OK¦1=Panne
Sortie relais pompe à essence en CC au +bat	8	1	1	0	1	0	1		0=OK¦1=Panne
Panne présente relais GMV 1	11	3	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée régulation ralenti	22	0	1	0	1	0	1		0=OK¦1=Panne
Sortie relais gmv2 en CC à la masse	9	4	1	0	1	0	1		0=OK¦1=Panne
FLGSCM2	9	0	8	0	1	0	1		
Sortie relais actuateurs en circuit ouvert	10	0	1	0	1	0	1		0=OK¦1=Panne
Sortie commande CTPH en court-circuit à la masse	19	6	1	0	1	0	1		0=OK¦1=Panne
Sortie voyant alerte eau en CC au +bat	8	7	1	0	1	0	1		0=OK¦1=Panne
Sortie relais gmv1 en CC à la masse	9	3	1	0	1	0	1		0=OK¦1=Panne
Injecteur 1 en CC à la masse	4	0	1	0	1	0	1		0=OK¦1=Panne
Injecteur 3 en circuit ouvert	5	2	1	0	1	0	1		0=OK¦1=Panne
Injecteur 4 en circuit ouvert	5	3	1	0	1	0	1		0=OK¦1=Panne
Sortie relais actuateurs en CC au +bat	8	0	1	0	1	0	1		0=OK¦1=Panne
Panne présente bloquage mécanique EGR	16	1	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée Climatisation	12	5	1	0	1	0	1		0=OK¦1=Panne
Sortie chauffage sonde aval en CC au +bat	13	5	1	0	1	0	1		0=OK¦1=Panne
Sortie chauffage sonde amont en CC à la masse	14	3	1	0	1	0	1		0=OK¦1=Panne
Sorties allumage 1 et 4 en CC à la masse.	4	4	1	0	1	0	1		0=OK¦1=Panne
Sortie climatisation en CC à la masse	9	5	1	0	1	0	1		0=OK¦1=Panne
Sortie chauffage sonde aval en circuit ouvert	15	5	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée relais actuateur	12	0	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée commande CTPH	22	6	1	0	1	0	1		0=OK¦1=Panne
FLGPPS3	16	0	8	0	1	0	1		
FLGPPS2	11	0	8	0	1	0	1		
FLGPPS1	6	0	8	0	1	0	1		
Panne présente relais de pompe à essence	11	1	1	0	1	0	1		0=OK¦1=Panne
Sortie purge canister en CC au +bat	13	2	1	0	1	0	1		0=OK¦1=Panne
FLGPPS4	21	0	8	0	1	0	1		
Sortie régulation ralenti en circuit ouvert	20	0	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée sorties allumage 1 et 4 .	7	4	1	0	1	0	1		0=OK¦1=Panne
Panne présente régulation ralenti	21	0	1	0	1	0	1		0=OK¦1=Panne
Sortie relais gmv1 en circuit ouvert	10	3	1	0	1	0	1		0=OK¦1=Panne
FLGSCO1	5	0	8	0	1	0	1		
FLGSCO3	15	0	8	0	1	0	1		
FLGSCO2	10	0	8	0	1	0	1		
Sortie purge canister en circuit ouvert	15	2	1	0	1	0	1		0=OK¦1=Panne
FLGSCO4	20	0	8	0	1	0	1		
Panne mémorisée puissance chauffage O2 Aval	17	6	1	0	1	0	1		0=OK¦1=Panne
Panne présente chauffage sonde Aval	16	5	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée voyant défaut	12	6	1	0	1	0	1		0=OK¦1=Panne
Panne présente relais GMV 2	11	4	1	0	1	0	1		0=OK¦1=Panne
Panne présente sorties allumage 1 et 4.	6	4	1	0	1	0	1		0=OK¦1=Panne
Injecteur 1 en circuit ouvert	5	0	1	0	1	0	1		0=OK¦1=Panne
Injecteur 4 en CC au +bat	3	3	1	0	1	0	1		0=OK¦1=Panne
FLGSCM4	19	0	8	0	1	0	1		
FLGSCM3	14	0	8	0	1	0	1		
Panne mémorisée sortie EGR	17	0	1	0	1	0	1		0=OK¦1=Panne
Sortie régulation ralenti en CC au +bat	18	0	1	0	1	0	1		0=OK¦1=Panne
Sortie relais gmv1 en CC au +bat	8	3	1	0	1	0	1		0=OK¦1=Panne
Injecteur 2 en circuit ouvert	5	1	1	0	1	0	1		0=OK¦1=Panne
"""
    private const val FA2 = """
Demande de ralenti accéléré	4	2	1	0	1	0	1		0=non demandé¦1=DEMANDE
Information GPL provenant de UCE GPL	3	7	1	0	1	0	1		0=absent¦1=PRESENT
Température d air copie trame 3	17	0	8	0	0.625	-40.0	1	°C	
Présence signal dent	6	0	1	0	1	0	1		0=absent¦1=PRESENT
Bruit moteur moyen dans fenetre de cliquetis copie trame 3	19	0	8	0	1	0	1		
Demande de mise en route du compresseur de clim	4	0	1	0	1	0	1		0=non demandée¦1=DEMANDEE
Régime moteur copie trame 3	10	0	16	0	1	0	1	tr/mn	
Position papillon pied Levé	3	2	1	0	1	0	1		0=inactif¦1=ACTIF
ETALOG1	5	0	8	0	1	0	1		
ETALOG2	6	0	8	0	1	0	1		
Contact frein à ouverture connecté	7	5	1	0	1	0	1		0=non connecté¦1=CONNECTE
Liaison bva-inj connectée	7	2	1	0	1	0	1		0=non connectée¦1=CONNECTEE
Code Appris - verlog 2	6	4	1	0	1	0	1		0=non appris¦1=APPRIS
CTPH cut state	5	3	1	0	1	0	1		0=inactif¦1=ACTIF
ETACNX1	7	0	8	0	1	0	1		
Stratégie reconnaissance cylindre 1 terminée	5	7	1	0	1	0	1		0=non terminée¦1=TERMINEE
Antivol état protégé - verlog 2	6	5	1	0	1	0	1		0=non protégé¦1=PROTEGE
Double boucle de richesse active	5	2	1	0	1	0	1		0=inactive¦1=ACTIVE
CTPH freeze state	5	4	1	0	1	0	1		0=inactif¦1=ACTIF
Niveau électirque contact frein à ouverture	4	7	1	0	1	0	1		0=non appuyé¦1=APPUYE
Tension de la sonde à oxygène amont copie trame 3	14	0	8	0	9.76	0	1	mV	
FLGPME1 copie trame 3	9	0	8	0	1	0	1		
Position Park ou Neutre	4	4	1	0	1	0	1		0=park¦1=neutre
Demande allumage MIL par la BVA	4	6	1	0	1	0	1		0=non demandé¦1=DEMANDE
Estompage de couple temporisé	4	5	1	0	1	0	1		0=non temporisé¦1=TEMPORISE
Tension de la sonde à oxygène aval copie trame 3	15	0	8	0	9.76	0	1	mV	
régulation ralenti active	5	1	1	0	1	0	1		0=inactive¦1=ACTIVE
régulation de richesse active	5	0	1	0	1	0	1		0=inactive¦1=ACTIVE
Contact marche arrière	3	6	1	0	1	0	1		0=absent¦1=PRESENT
Pied à fond	3	1	1	0	1	0	1		0=inactif¦1=ACTIF
Couple moteur effectif	20	0	8	0	2.0	-100.0	1	Nm	
Niveau électrique du pressostat de direction assistée	3	4	1	0	1	0	1		0=inactif¦1=ACTIF
Pression collecteur admission copie trame 3	12	0	8	0	3.7	103.0	1	mb	
ETAIN1	3	0	8	0	1	0	1		
Position papillon copie trame 3	13	0	8	0	0.4	0	1	°	
Tension batterie copie trame 3	18	0	8	0	0.032	8.0	1	V	
ETAIN2	4	0	8	0	1	0	1		
Température d eau copie trame 3	16	0	8	0	0.625	-40.0	1	°C	
Capteur vitesse véhicule connecté	7	0	1	0	1	0	1		0=non connecté¦1=CONNECTE
Activation of interior heater fan	3	3	1	0	1	0	1		0=inactif¦1=ACTIF
Liaison gpl-inj connecté	7	7	1	0	1	0	1		0=non connectée¦1=CONNECTEE
Contact marche arrière connecté	7	6	1	0	1	0	1		0=non connecté¦1=CONNECTE
Pressostat de direction assistée connecté	7	4	1	0	1	0	1		0=non connecté¦1=CONNECTE
FLGPPE1 copie trame 3	8	0	8	0	1	0	1		
Liaison clim-inj connectée	7	3	1	0	1	0	1		0=non connectée¦1=CONNECTEE
Information + APC	3	0	1	0	1	0	1		0=absent¦1=PRESENT
Bonne prédiction de phasage de l arbre à cames	5	6	1	0	1	0	1		0=mauvaise¦1=BONNE
"""
    private const val FA3 = """
ETAIN2 copie trame 4	4	0	8	0	1	0	1		
Puissance absorbée par compresseur de clim	15	0	8	0	20.0	0	1	W	
Pression du circuit de climatisation	16	0	8	0	0.2	0	1	Bar	
FLGPME2 copie trame 4	9	0	8	0	1	0	1		
ETACNX1 copie trame 4	7	0	8	0	1	0	1		
FLGPME3 copie trame 4	11	0	8	0	1	0	1		
ETALOG2 copie trame 4	6	0	8	0	1	0	1		
FLGPPE3 copie trame 4	10	0	8	0	1	0	1		
ETAIN1 copie trame 4	3	0	8	0	1	0	1		
FLGPPE2 copie trame 4	8	0	8	0	1	0	1		
Vitesse du véhicule copie trame 4	14	0	8	0	1	0	1	km/h	
Régime moteur copie trame 4	12	0	16	0	1	0	1	tr/min	
ETALOG1 copie trame 4	5	0	8	0	1	0	1		
"""
    private const val FA4 = """
Groupe motoventilateur actif en vitesse 2	3	3	1	0	1	0	1		0=inactif¦1=ACTIF
Groupe motoventilateur actif en vitesse 1	3	2	1	0	1	0	1		0=inactif¦1=ACTIF
Régime moteur copie trame 5	13	0	16	0	1	0	1	tr/mn	
Etat de la commande chauffage de la sonde amont	4	4	1	0	1	0	1		0=inactif¦1=ACTIF
Etat du relais de la pompe à air	3	4	1	0	1	0	1		0=inactif¦1=ACTIF
Etat de la commande chauffage de la sonde aval	4	3	1	0	1	0	1		0=inactif¦1=ACTIF
Etat de la commande du compresseur de climatisation	5	5	1	0	1	0	1		0=inactif¦1=ACTIF
FLGPMS1 copie trame 5	12	0	8	0	1	0	1		
Etat du voyant changement de rapport	4	6	1	0	1	0	1		0=inactif¦1=ACTIF
ETALOG2 copie trame 5	7	0	8	0	1	0	1		
FLGSCO1 copie trame 5	10	0	8	0	1	0	1		
Etat du relais des actuateurs	3	0	1	0	1	0	1		0=inactif¦1=ACTIF
ETALOG1 copie trame 5	6	0	8	0	1	0	1		
Etat de l EGR	4	0	1	0	1	0	1		0=inactif¦1=ACTIF
FLGSCP1 copie trame 5	8	0	8	0	1	0	1		
FLGSCM1 copie trame 5	9	0	8	0	1	0	1		
Temps d injection copie trame 5	16	0	16	0	4.0	0	1000.0	ms	
Etat de la purge canister	4	1	1	0	1	0	1		0=inactive¦1=ACTIVE
State of additional heater relay 1	5	0	1	0	1	0	1		0=inactif¦1=ACTIF
State of additional heater relay 3	5	3	1	0	1	0	1		0=inactif¦1=ACTIF
State of additional heater relay 2	5	1	1	0	1	0	1		0=inactif¦1=ACTIF
Etat de la commande du décaleur arbre à came	5	4	1	0	1	0	1		0=inactif¦1=ACTIF
Etat du relais de la pompe à essence	3	1	1	0	1	0	1		0=inactif¦1=ACTIF
Avance à l allumage copie trame 5	18	0	8	0	0.375	-23.625	1	°V	
Etat du voyant défaut	3	7	1	0	1	0	1		0=inactif¦1=ACTIF
ETAOUT3	5	0	8	0	1	0	1		
ETAOUT2	4	0	8	0	1	0	1		
ETAOUT1	3	0	8	0	1	0	1		
Etat du voyant MIL	3	5	1	0	1	0	1		0=inactif¦1=ACTIF
FLGPPS1 copie trame 5	11	0	8	0	1	0	1		
Facteur enrichissement régulation de richesse copie trame 5	15	0	8	0	1	0	1		
Etat du voyant d alerte de température d eau	3	6	1	0	1	0	1		0=inactif¦1=ACTIF
"""
    private const val FA5 = """
ETAOUT1 copie trame 6	3	0	8	0	1	0	1		
FLGPPS2 copie trame 6	11	0	8	0	1	0	1		
FLGPMS2 copie trame 6	12	0	8	0	1	0	1		
Régime moteur copie trame 6	13	0	16	0	1	0	1	tr/mn	
FLGSCM2 copie trame 6	9	0	8	0	1	0	1		
FLGSCO2 copie trame 6	10	0	8	0	1	0	1		
ETALOG2 copie trame 6	7	0	8	0	1	0	1		
ETAOUT3 copie trame 6	5	0	8	0	1	0	1		
ETAOUT2 copie trame 6	4	0	8	0	1	0	1		
ETALOG1 copie trame 6	6	0	8	0	1	0	1		
FLGSCP2 copie trame 6	8	0	8	0	1	0	1		
Facteur enrichissement régulation richesse copie trame 6	15	0	8	0	1	0	1		
"""
    private const val FA6 = """
FLGSCO3 copie trame 7	10	0	8	0	1	0	1		
Facteur enrichissement régulation richesse copie trame 7	15	0	8	0	1	0	1		
Régime moteur copie trame 7	13	0	16	0	1	0	1	tr/mn	
FLGPPS3 copie trame 7	11	0	8	0	1	0	1		
ETAOUT1 copie trame 7	3	0	8	0	1	0	1		
Tension de la sonde à oxygène amont copie trame 7	18	0	8	0	9.76	0	1	mV	
Tension de la sonde à oxygène aval copie trame 7	19	0	8	0	9.76	0	1	mV	
ETALOG1 copie trame 7	6	0	8	0	1	0	1		
ETALOG2 copie trame 7	7	0	8	0	1	0	1		
ETAOUT3 copie trame 7	5	0	8	0	1	0	1		
FLGSCM3 copie trame 7	9	0	8	0	1	0	1		
ETAOUT2 copie trame 7	4	0	8	0	1	0	1		
Consigne absolue d ouverture de la vanne EGR	16	0	8	0	1	0	1		
RCO vanne purge canister copie trame 7	17	0	8	0	1	0	1	%	
FLGPMS3 copie trame 7	12	0	8	0	1	0	1		
FLGSCP3 copie trame 7	8	0	8	0	1	0	1		
"""
    private const val FA7 = """
FLGSCM4 copie trame 8	9	0	8	0	1	0	1		
RCO théorique régulation ralenti copie trame 8	16	0	8	0	0.392	0	1	%	
FLGSCO4 copie trame 8	10	0	8	0	1	0	1		
ETAOUT1 copie trame 8	3	0	8	0	1	0	1		
FLGSCP4 copie trame 8	8	0	8	0	1	0	1		
FLGPPS4 copie trame 8	11	0	8	0	1	0	1		
Nombre de thermoplongeurs activés	19	0	8	0	1	0	1		
Régime de consigne régulation ralenti	17	0	16	0	1	0	1	tr/min	
Facteur enrichissement régulation richesse	15	0	8	0	1	0	1		
FLGPMS4 copie trame 8	12	0	8	0	1	0	1		
ETAOUT2 copie trame 8	4	0	8	0	1	0	1		
ETALOG1 copie trame 8	6	0	8	0	1	0	1		
ETAOUT3 copie trame 8	5	0	8	0	1	0	1		
ETALOG2 copie trame 8	7	0	8	0	1	0	1		
Régime moteur copie trame 8	13	0	16	0	1	0	1	tr/mn	
"""
    private const val FA8 = """
Pannes présentes sur une des sorties	10	1	1	0	1	0	1		0=OK¦1=Panne
ETAOUT1 copie trame 9	7	0	8	0	1	0	1		
Pannes mémorisées sur une des entrées	10	2	1	0	1	0	1		0=OK¦1=Panne
Pannes présentes sur une des entrées	10	0	1	0	1	0	1		0=OK¦1=Panne
ETAIN2 copie trame 9	6	0	8	0	1	0	1		
Pannes mémorisées sur une des sorties	10	3	1	0	1	0	1		0=OK¦1=Panne
ETAPAN1	10	0	4	0	1	0	1		
ETALOG1 copie trame 9	3	0	8	0	1	0	1		
ETAOUT2 copie trame 9	8	0	8	0	1	0	1		
ETAOUT3 copie trame 9	9	0	8	0	1	0	1		
ETALOG2 copie trame 9	4	0	8	0	1	0	1		
ETAIN1 copie trame 9	5	0	8	0	1	0	1		
"""
    private const val FA9 = """
Appr TLZ défaut demi-tour cible pour cyl 2 et 3	19	0	16	1	1	0	1		
Tension de la sonde à oxygène amont	10	0	8	0	9.76	0	1	mV	
Appr TLZ défaut demi-tour cible pour cyl 1 et 4	17	0	16	1	1	0	1		
Bruit moteur moyen dans fenetre de cliquetis	15	0	8	0	1	0	1		
Température eau mesurée	8	0	8	0	0.625	-40.0	1	°C	
Appr TRZ défaut demi-tour cible pour cyl 2 et 3	25	0	16	0	1	0	1		
Régime moteur	3	0	16	0	1	0	1	tr/mn	
Température air mesurée	9	0	8	0	0.625	-40.0	1	°C	
Position mesurée du potentiomètre papillon	7	0	8	0	0.4	0	1	°	
Pression atmospherique	14	0	8	0	-3.7	1043.5	1	mb	
Appr TRZ défaut demi-tour cible pour cyl 1 et 4	23	0	16	0	1	0	1		
Tension de la sonde à oxygène aval	11	0	8	0	9.76	0	1	mV	
Valeur max corr avance due au cliquetis	16	0	8	0	0.375	0	1	°V	
Pression collecteur absolue mesurée	6	0	8	0	3.7	103.0	1	mb	
Tension batterie mesurée	5	0	8	0	0.032	8.0	1	V	
Vitesse du véhicule	12	0	8	0	1	0	1	km/h	
Appr TLZ défauts cible correction basse fréquence	21	0	16	1	1	0	1		
Valeur d apprentissage de la position pied levé	13	0	8	0	1	0	1	°	
"""
    private const val FAA = """
Facteur enrichissement régulation richesse copie trame 11	5	0	8	0	1	0	1		
Offset d apprentissage de la régulation de richesse	6	0	8	0	1	0	1		
Consigne absolue ouverture vanne EGR	11	0	8	0	1	0	1		
Régime moteur copie trame 11	3	0	16	0	1	0	1	tr/min	
RCO vanne purge canister	12	0	8	0	1	0	1	%	
Consommation instantanée de carburant	13	0	16	0	1	0	100.0	L/H	
Gain apprentissage régulation richesse	7	0	8	0	1	0	1		
Avance à l allumage à chaque PMH	10	0	8	0	0.375	-23.625	1	°V	
Indice d'octane reconnu entre un min et un max	15	0	8	0	1	0	1		
Temps injection réel	8	0	16	0	0.004	0	1	ms	
"""
    private const val FAB = """
Régime de consigne ralenti copie trame 12	5	0	16	0	1	0	1	tr/mn	
Nb de pas corrigé fermeture vanne RR	12	0	8	0	1	0	1	pas	
Intégral du RCO théorique régulation ralenti	7	0	8	0	1	0	1		
Correction de régime ralenti après-vente	10	0	8	0	16.0	0	1	tr/min	
Régime moteur copie trame 12	3	0	16	0	1	0	1	tr/mn	
Filtered alternator mechanical power	17	0	16	0	10.0	0	1	W	
Air temperature upstream normal interior heater	15	0	8	0	0.625	-40.0	1	°C	
Nb de pas corrigé ouverture vanne RR	11	0	8	0	1	0	1	pas	
Filtered alternator rotor PWM	16	0	8	0	100.0	0	255.0	%	
Authorized number of additional interior heaters	14	0	8	0	1	0	1		
RCO théorique régulation ralenti	9	0	8	0	0.392	0	1	%	
Valeur d apprentissage de la regulation ralenti	8	0	8	1	0.392	0	1	%	
Number of required additional interior heaters (thermic requirement)	13	0	8	0	1	0	1		
"""
    private const val FAC = """
Panne présente injecteur 3 à la MsT	3	2	1	0	1	0	1		0=OK¦1=Panne
Panne présente voyant MIL à la MsT	5	7	1	0	1	0	1		0=OK¦1=Panne
Panne présente injecteur 1 à la MsT	3	0	1	0	1	0	1		0=OK¦1=Panne
Panne présente injecteur 4 à la MsT	3	3	1	0	1	0	1		0=OK¦1=Panne
Panne présente sorties allumage 1 et 4 à la MsT.	3	4	1	0	1	0	1		0=OK¦1=Panne
Panne présente sortie décaleur arbre à came à la MsT	6	7	1	0	1	0	1		0=OK¦1=Panne
Panne présente sortie chauffage sonde aval à MsT	5	5	1	0	1	0	1		0=OK¦1=Panne
Panne présente sortie pompe à air à la MsT	4	2	1	0	1	0	1		0=OK¦1=Panne
Panne présente sortie relais gmv2 à la MsT	4	4	1	0	1	0	1		0=OK¦1=Panne
Panne présente sortie relais gmv1 à la MsT	4	3	1	0	1	0	1		0=OK¦1=Panne
Panne présente relais actuateur à la MsT	4	0	1	0	1	0	1		0=OK¦1=Panne
Panne présente sorties allumage 2 et 3 à la MsT.	3	5	1	0	1	0	1		0=OK¦1=Panne
Panne présente sortie pompe essence à la MsT	4	1	1	0	1	0	1		0=OK¦1=Panne
Panne présente sortie voyant défaut à la MsT	4	6	1	0	1	0	1		0=OK¦1=Panne
Panne présente sortie climatisation à la MsT	4	5	1	0	1	0	1		0=OK¦1=Panne
Panne présente sortie purge canister à la MsT	5	2	1	0	1	0	1		0=OK¦1=Panne
Panne présente sortie voyant alerte eau à la MsT	4	7	1	0	1	0	1		0=OK¦1=Panne
FLGMTS1	3	0	8	0	1	0	1		
FLGMTS2	4	0	8	0	1	0	1		
FLGMTS3	5	0	8	0	1	0	1		
FLGMTS4	6	0	8	0	1	0	1		
Panne  présente sortie EGR à la MsT	5	0	1	0	1	0	1		0=OK¦1=Panne
Panne présente sortie chauffage sonde amont à la MsT	5	3	1	0	1	0	1		0=OK¦1=Panne
Panne présente injecteur 2 à la MsT	3	1	1	0	1	0	1		0=OK¦1=Panne
Panne présente sortie régulation ralenti à la MsT	6	0	1	0	1	0	1		0=OK¦1=Panne
"""
    private const val FAD = """
Panne mémorisée OBD misfire catalyseur	9	6	1	0	1	0	1		0=OK¦1=Panne
Panne présente OBD EGR	7	3	1	0	1	0	1		0=OK¦1=Panne
Diagnostic de l air secondaire pris en compte par le séquenceur	3	1	1	0	1	0	1		0=non effectué¦1=EFFECTUE
Diagnostic de l EGR	6	3	1	0	1	0	1		0=non effectué¦1=EFFECTUE
Misfire sur cylindre 3	4	2	1	0	1	0	1		0=non détecté¦1=DETECTE
Misfire sur cylindre 2	4	1	1	0	1	0	1		0=non détecté¦1=DETECTE
Misfire sur cylindre 1	4	0	1	0	1	0	1		0=non détecté¦1=DETECTE
Requete de diagnostic du catalyseur	5	2	1	0	1	0	1		0=non effectué¦1=EFFECTUE
Séquenceur OBD en mode controle en chaine	3	4	1	0	1	0	1		0=non¦1=OUI
Misfire sur cylindre 4	4	3	1	0	1	0	1		0=non détecté¦1=DETECTE
Panne présente OBD sondes à oxygène	7	0	1	0	1	0	1		0=OK¦1=Panne
Diagnostic du catalyseur pris en compte par le séquenceur	3	2	1	0	1	0	1		0=non effectué¦1=EFFECTUE
Diagnostic des sondes pris en compte par le séquenceur	3	0	1	0	1	0	1		0=non effectué¦1=EFFECTUE
Panne validée OBD misfire pollution	8	5	1	0	1	0	1		0=OK¦1=Panne
ETAOBD4	6	0	8	0	1	0	1		
Diagnostic du fuel system pris en compte par le séqu	3	7	1	0	1	0	1		0=non effectué¦1=EFFECTUE
FLGPVF1	8	0	8	0	1	0	1		
Diagnostic circuit essence terminé	6	5	1	0	1	0	1		0=inactif¦1=ACTIF
ETAOBD1	3	0	8	0	1	0	1		
ETAOBD2	4	0	8	0	1	0	1		
ETAOBD3	5	0	8	0	1	0	1		
Panne mémorisée OBD EGR	9	3	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée OBD fuel system	9	7	1	0	1	0	1		0=OK¦1=Panne
Panne validée OBD fuel system	8	7	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée OBD sondes à oxygène	9	0	1	0	1	0	1		0=OK¦1=Panne
Diagnostic du misfire pris en compte par le séquenceur	3	6	1	0	1	0	1		0=non effectué¦1=EFFECTUE
demande MIL sur Panne OBD sondes à oxygène	10	0	1	0	1	0	1		0=non demandé¦1=DEMANDE
Panne mémorisée OBD air secondaire	9	1	1	0	1	0	1		0=OK¦1=Panne
FLGPPF1	7	0	8	0	1	0	1		
Panne validée OBD air secondaire	8	1	1	0	1	0	1		0=OK¦1=Panne
Compteur de pmh diag misfire controle en chaine	11	0	16	0	1	0	1	pmh	
Résistance de chauffage de la sonde aval	22	0	8	0	0.1664	0	1	Ohms	
Panne présente OBD misfire catalyseur	7	6	1	0	1	0	1		0=OK¦1=Panne
demande MIL sur Panne OBD misfire pollution	10	5	1	0	1	0	1		0=non demandé¦1=DEMANDE
Requete de diagnostic du Misfire	5	6	1	0	1	0	1		0=non effectué¦1=EFFECTUE
Résistance de chauffage de la sonde amont	21	0	8	0	0.1664	0	1	Ohms	
Panne validée OBD catalyseur	8	2	1	0	1	0	1		0=OK¦1=Panne
Panne présente OBD catalyseur	7	2	1	0	1	0	1		0=OK¦1=Panne
Diagnostic de l EGR pris en compte par le séquenceur	3	3	1	0	1	0	1		0=non effectué¦1=EFFECTUE
Diagnostic catalyseur fait	6	2	1	0	1	0	1		0=inactif¦1=ACTIF
Panne présente OBD misfire pollution	7	5	1	0	1	0	1		0=OK¦1=Panne
Offset double boucle moyen mini pour le diag coté pauvre	17	0	8	1	1	0	1		
Diagnostics électriques faits	6	6	1	0	1	0	1		0=inactif¦1=ACTIF
demande allumage MIL sur Panne OBD EGR	10	3	1	0	1	0	1		0=non demandé¦1=DEMANDE
diagnostic misfire fait	6	4	1	0	1	0	1		0=inactif¦1=ACTIF
Requete de diagnostic du Fuel System	5	7	1	0	1	0	1		0=non effectué¦1=EFFECTUE
FLGMILF1	10	0	8	0	1	0	1		
Diagnostic des sondes fait	6	0	1	0	1	0	1		0=inactif¦1=ACTIF
Panne mémorisée OBD misfire pollution	9	5	1	0	1	0	1		0=OK¦1=Panne
Requete de diagnostic des sondes	5	0	1	0	1	0	1		0=non effectué¦1=EFFECTUE
demande allumMIL sur Panne  OBD fuel system	10	7	1	0	1	0	1		0=non demandé¦1=DEMANDE
Panne validée OBD sondes à oxygène	8	0	1	0	1	0	1		0=OK¦1=Panne
Offset double boucle moyen maxi pour le diag coté riche	16	0	8	1	1	0	1		
Requete de diagnostic de l air secondaire	5	1	1	0	1	0	1		0=non effectué¦1=EFFECTUE
FLGPOBF1	9	0	8	0	1	0	1		
Offset double boucle moyen pour le diag	15	0	8	1	1	0	1		
demande allum MIL sur Panne  OBD misfire catalyseur	10	6	1	0	1	0	1		0=non demandé¦1=DEMANDE
pourcentage du misfire lors du controle en chaine	13	0	8	0	0.39	0	1	%	
Activitée de la sonde aval mesurée	18	0	8	0	1	0	1		
Panne présente OBD fuel system	7	7	1	0	1	0	1		0=OK¦1=Panne
demande MIL sur Panne OBD air secondaire	10	1	1	0	1	0	1		0=non demandé¦1=DEMANDE
Seuil de panne catalyseur	19	0	8	0	1	0	1		
Panne présente OBD air secondaire	7	1	1	0	1	0	1		0=OK¦1=Panne
Panne validée OBD EGR	8	3	1	0	1	0	1		0=OK¦1=Panne
Nombre de faux basculements	20	0	8	0	1	0	1		
Période moyenne de la sonde amont	14	0	8	0	16.0	0	1000.0	ms	
Panne mémorisée OBD catalyseur	9	2	1	0	1	0	1		0=OK¦1=Panne
Diagnostic de l air secondaire	6	1	1	0	1	0	1		0=non effectué¦1=EFFECTUE
Requete de diagnostic de l EGR	5	3	1	0	1	0	1		0=non effectué¦1=EFFECTUE
Compteur de kilomètres parcourus avec la MIL allumée	23	0	16	0	1	0	1	km	
demande allumage MIL Panne OBD catalyseur	10	2	1	0	1	0	1		0=non demandé¦1=DEMANDE
Panne validée OBD misfire catalyseur	8	6	1	0	1	0	1		0=OK¦1=Panne
"""
    private const val FAE = """
Type de liaison bva-inj	4	4	1	0	1	0	1		0=filaire¦1=multiplexée
Sauvegarde période moyenne maxi de la sonde	10	0	8	0	16.0	0	1	ms	
Config egr	3	2	1	0	1	0	1		0=sans objet -valeur par défaut-¦1=sans objet 
Config bva	3	4	1	0	1	0	1		0=utilise les stratégies liées à la BVM¦1=utilise les stratégies liées à la BVA
Configuration contact frein à ouverture	7	3	1	0	1	0	1		0=non géré¦1=GERE
CFGOBD1	6	0	8	0	1	0	1		
Configuration thermoplongeurs	7	0	1	0	1	0	1		0=gère pas thermoplongeurs¦1=gère thermoplongeurs
Configuration liaison MUX abs	7	1	1	0	1	0	1		0=non multiplexée¦1=multiplexée
Sauvegarde seuil de panne catalyseur	14	0	8	0	1	0	1		
Commande du voyant mil sur misfire catalyseur	5	1	1	0	1	0	1		0=ne gère pas le voyant MIL¦1=gère le voyant MIL sur panne misfire cata
Config sonde à oxygène aval	3	6	1	0	1	0	1		0=utilise pas le signal sonde O2¦1=utilise le signal sonde O2
Sauvegarde variable d'interpolation "debit d'air" moteur pour diag cata (Poids forts)	12	0	8	0	1	0	1		
Stratégie climatisation sur petit moteur	5	5	1	0	1	0	1		0=gère pas clim à cyl variable ¦1=gère clim à cyl variable sans infoPA
Number of additional interior heater elements	15	0	8	0	1	0	1		
Commande du voyant mil sur demande bva	5	2	1	0	1	0	1		0=ne gère pas le voyant MIL¦1=gère le voyant MIL sur demande BVA
CFGENV2	7	0	8	0	1	0	1		
CFGENV1	3	0	8	0	1	0	1		
Config vvt	3	7	1	0	1	0	1		0=gère pas la sortie vvt¦1=gère la sortie vvt
Stratégie de reconnaissance du cylindre 1	5	3	1	0	1	0	1		0=utilise pas les stratégies logicielles ¦1=utilise les stratégies logicielles recocyl1
Séquenceur OBD diag air secondaire	6	1	1	0	1	0	1		0=non¦1=OUI
Sauvegarde période moyenne de la sonde amont	9	0	8	0	16.0	0	1	ms	
Config pompe à air	3	3	1	0	1	0	1		0=sans objet -valeur par défaut-¦1=sans objet 
Type de liaison clim-inj	4	2	1	0	1	0	1		0=filaire¦1=multiplexée
Config voyant mil	3	1	1	0	1	0	1		0=gère pas allumage voyant MIL¦1=gère allumage voyant MIL
Séquenceur OBD diag sondes a oxygène	6	0	1	0	1	0	1		0=non¦1=OUI
Configuration CTP habitacle	7	4	1	0	1	0	1		0=géré la sortie VVT¦1=géré la sortie CTPH
Configuration voyant changement de rapport	7	2	1	0	1	0	1		0=gère pas et commande pas voyant¦1=gère et commande voyant
Séquenceur OBD diag EGR	6	3	1	0	1	0	1		0=non¦1=OUI
Type de phasage arbre à came	4	1	1	0	1	0	1		0=issue de info capteur vvt¦1=issue des stratégies logicielles
Type verrou logiciel	8	2	1	0	1	0	1		0=Verrou 2¦1=Verrou 3
Type vitesse véhicule	8	1	1	0	1	0	1		0=Filaire¦1=MUX
Type de capteur de température d air	4	6	1	0	1	0	1		0=CTN de type A¦1=CTN de type D
CFGLOG1	5	0	8	0	1	0	1		
TYPENV2	8	0	8	0	1	0	1		
TYPENV1	4	0	8	0	1	0	1		
Sauvegarde debit d'air moteur filtre pour le diag sonde (Poids forts)	11	0	8	0	1	0	1		
Type de pompe à essence	4	0	1	0	1	0	1		0=à débit normal¦1=à débit réduit
Climatisation boucle froide dans injection	5	6	1	0	1	0	1		0=non¦1=OUI
Config sonde à oxygène amont	3	5	1	0	1	0	1		0=utilise pas le signal sonde O2¦1=utilise le signal sonde O2
Séquenceur OBD diag fuel system	6	7	1	0	1	0	1		0=non¦1=OUI
Type de compresseur de climatisation	4	3	1	0	1	0	1		0=cylindrée variable¦1=cylindrée fixe
Config autorisant pilotage des gmvs moteur tournant	3	0	1	0	1	0	1		0=ne gère pas les GMV¦1=gère les GMV moteur tournant
Configuation de l'utilisation de la borne DF au ralenti	7	5	1	0	1	0	1		0=Sans borne DF¦1=Avec borne DF
Type de bobines d allumage	4	5	1	0	1	0	1		0=double sorties¦1=crayons
Séquenceur OBD diag misfire	6	6	1	0	1	0	1		0=non¦1=OUI
Séquenceur OBD diag catalyseur	6	2	1	0	1	0	1		0=non¦1=OUI
Sauvegarde activité de la sonde aval mesurée (Poids forts)	13	0	8	0	1	0	1		
Stratégie de mémophasage	5	4	1	0	1	0	1		0=non utilisée¦1=utilisée
Type de sonde à oxygène (type sonde 1 fil)	4	7	1	0	1	0	1		0=inactif¦1=ACTIF
Type de la sortie VVT ou thermoplongeurs	8	0	1	0	1	0	1		0=sortie logique¦1=sortie PWM
Pilotage du gmv1 par la commande de clima	5	0	1	0	1	0	1		0=GMV1 non commandé¦1=GMV1 commandé
"""
    private const val FAF = """
Panne validée OBD sonde à oxygène amont	9	0	1	0	1	0	1		0=OK¦1=Panne
panne mémo OBD sortie puissance sur chauffage sonde aval	19	6	1	0	1	0	1		0=OK¦1=Panne
allum MIL sur Panne  OBD sur la liaison BVA	8	6	1	0	1	0	1		0=non demandé¦1=DEMANDE
Panne validée OBD défaut cible volant moteur	6	2	1	0	1	0	1		0=OK¦1=Panne
Panne validée OBD du capteur de cliquetis	3	6	1	0	1	0	1		0=OK¦1=Panne
demande allum MIL sur panne OBD sorties allumage 2 et 3	14	5	1	0	1	0	1		0=non demandé¦1=DEMANDE
Panne mémorisée OBD capteur pression collecteur	4	2	1	0	1	0	1		0=OK¦1=Panne
Panne validée OBD injecteur 3	12	2	1	0	1	0	1		0=OK¦1=Panne
Panne validée OBD injecteur 2	12	1	1	0	1	0	1		0=OK¦1=Panne
Panne validée OBD injecteur 1	12	0	1	0	1	0	1		0=OK¦1=Panne
allum MIL sur Panne OBD sonde à oxygène aval	11	1	1	0	1	0	1		0=non demandé¦1=DEMANDE
allum MIL sur panne sortie purge canister	20	2	1	0	1	0	1		0=non demandé¦1=DEMANDE
Panne validée OBD relais pompe à essence	15	1	1	0	1	0	1		0=OK¦1=Panne
Panne validée OBD sorties allumage 2 et 3	12	5	1	0	1	0	1		0=OK¦1=Panne
allum MIL sur panne sortie chauffage sonde amont	20	3	1	0	1	0	1		0=non demandé¦1=DEMANDE
allum MIL Panne OBD sonde à oxygène amont	11	0	1	0	1	0	1		0=non demandé¦1=DEMANDE
allum MIL sur Panne OBD défaut cible volant moteur	8	2	1	0	1	0	1		0=non demandé¦1=DEMANDE
Panne mémorisée OBD capteur de cliquetis	4	6	1	0	1	0	1		0=OK¦1=Panne
panne validée OBD chauffage de la sonde aval	18	5	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée OBD sonde à oxygène aval	10	1	1	0	1	0	1		0=OK¦1=Panne
Panne validée OBD absence signal dent	6	1	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée OBD absence signal dent	7	1	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée OBD du potentiomètre EGR	10	2	1	0	1	0	1		0=OK¦1=Panne
allum MIL Panne OBD capteur pression absolue	5	2	1	0	1	0	1		0=non demandé¦1=DEMANDE
allum MIL sur Panne OBD du potentiomètre EGR	11	2	1	0	1	0	1		0=non demandé¦1=DEMANDE
allum MIL sur Panne OBD absence signal dent	8	1	1	0	1	0	1		0=non demandé¦1=DEMANDE
Panne validée régulation ralenti	21	0	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée EOBD régulation ralenti	22	0	1	0	1	0	1		0=OK¦1=Panne
allum MIL sur panne puissance chauffage sonde amont	20	4	1	0	1	0	1		0=non demandé¦1=DEMANDE
allum MIL Panne OBD capteur température air	5	5	1	0	1	0	1		0=non demandé¦1=DEMANDE
FLGMILE2	8	0	8	0	1	0	1		
FLGMILE3	11	0	8	0	1	0	1		
FLGMILE1	5	0	8	0	1	0	1		
Panne mémorisée OBD sortie pompe à essence	16	1	1	0	1	0	1		0=OK¦1=Panne
allum MIL Panne OBD capteur température eau	5	4	1	0	1	0	1		0=non demandé¦1=DEMANDE
Panne validée OBD sortie EGR	18	0	1	0	1	0	1		0=OK¦1=Panne
Panne validée OBD du potentiomètre EGR	9	2	1	0	1	0	1		0=OK¦1=Panne
allum MIL sur panne sur blocage méca de EGR	20	1	1	0	1	0	1		0=non demandé¦1=DEMANDE
Demande allumage MIL sur panne EOBD régulation ralenti	23	0	1	0	1	0	1		0=non demandée¦1=DEMANDEE
Panne mémorisée OBD sonde à oxygène amont	10	0	1	0	1	0	1		0=OK¦1=Panne
Panne validée OBD injecteur 4	12	3	1	0	1	0	1		0=OK¦1=Panne
allumage MIL Panne  OBD potentiomètre papillon	5	3	1	0	1	0	1		0=non demandé¦1=DEMANDE
panne validée OBD chauffage de la sonde amont	18	3	1	0	1	0	1		0=OK¦1=Panne
FLGPOBS4	22	0	8	0	1	0	1		
FLGPOBS1	13	0	8	0	1	0	1		
FLGPOBS3	19	0	8	0	1	0	1		
FLGPOBS2	16	0	8	0	1	0	1		
panne validée OBD voyant MIL	18	7	1	0	1	0	1		0=OK¦1=Panne
panne validée OBD purge canister	18	2	1	0	1	0	1		0=OK¦1=Panne
Panne validée OBD sorties allumage 1 et 4	12	4	1	0	1	0	1		0=OK¦1=Panne
allu MIL sur Panne  OBD sortie pompe à essence	17	1	1	0	1	0	1		0=non demandé¦1=DEMANDE
panne mémorisée OBD sortie voyant MIL	19	7	1	0	1	0	1		0=OK¦1=Panne
allumage MIL sur panne OBD sortie EGR	20	0	1	0	1	0	1		0=non demandé¦1=DEMANDE
Panne validée OBD du capteur de vitesse véhicule	6	0	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée OBD sorties allumage 1 et 4	13	4	1	0	1	0	1		0=OK¦1=Panne
FLGPOBE3	10	0	8	0	1	0	1		
FLGPOBE2	7	0	8	0	1	0	1		
FLGPOBE1	4	0	8	0	1	0	1		
Panne mémorisée OBD potentiomètre papillon	4	3	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée OBD capteur température eau	4	4	1	0	1	0	1		0=OK¦1=Panne
Panne validée OBD sonde à oxygène aval	9	1	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée OBD injecteur 4	13	3	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée OBD injecteur 3	13	2	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée OBD injecteur 2	13	1	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée OBD injecteur 1	13	0	1	0	1	0	1		0=OK¦1=Panne
panne validée OBD de puissance sur chauffage sonde amont	18	4	1	0	1	0	1		0=OK¦1=Panne
panne mémorisée OBD sur blocage méca de EGR	19	1	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée OBD défaut cible volant moteur	7	2	1	0	1	0	1		0=OK¦1=Panne
panne validée OBD sur bloquage méca de EGR	18	1	1	0	1	0	1		0=OK¦1=Panne
allumage MIL sur panne puissance chauffage sonde aval	20	6	1	0	1	0	1		0=non demandé¦1=DEMANDE
FLGMILS1	14	0	8	0	1	0	1		
FLGMILS2	17	0	8	0	1	0	1		
FLGMILS3	20	0	8	0	1	0	1		
FLGMILS4	23	0	8	0	1	0	1		
panne mémo OBD sortie puissance sur chauffage sonde amont	19	4	1	0	1	0	1		0=OK¦1=Panne
panne mémo OBD sortie chauffage sonde aval	19	5	1	0	1	0	1		0=OK¦1=Panne
demande allum MIL sur Panne OBD injecteur 2	14	1	1	0	1	0	1		0=non demandé¦1=DEMANDE
demande allum MIL sur Panne OBD injecteur 3	14	2	1	0	1	0	1		0=non demandé¦1=DEMANDE
demande allum MIL sur Panne OBD injecteur 4	14	3	1	0	1	0	1		0=non demandé¦1=DEMANDE
allum MIL sur Panne OBD capteur vitesse véhicule	8	0	1	0	1	0	1		0=non demandé¦1=DEMANDE
demande allum MIL sur panne OBD sorties allumage 1 et 4	14	4	1	0	1	0	1		0=non demandé¦1=DEMANDE
Panne mémorisée OBD capteur température air	4	5	1	0	1	0	1		0=OK¦1=Panne
Panne validée OBD du potetiomètre papillon	3	3	1	0	1	0	1		0=OK¦1=Panne
Panne validée OBD sortie relais pompe à air	15	2	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée OBD sortie  relais pompe à air	16	2	1	0	1	0	1		0=OK¦1=Panne
panne validée OBD de puissance sur chauffage sonde aval	18	6	1	0	1	0	1		0=OK¦1=Panne
allumage MIL Panne  OBD capteur de cliquetis	5	6	1	0	1	0	1		0=non demandé¦1=DEMANDE
FLGPVS1	12	0	8	0	1	0	1		
FLGPVS3	18	0	8	0	1	0	1		
FLGPVS2	15	0	8	0	1	0	1		
demande allum MIL sur Panne  OBD injecteur 1	14	0	1	0	1	0	1		0=non demandé¦1=DEMANDE
FLGPVS4	21	0	8	0	1	0	1		
Panne validée OBD du capteur pression collecteur	3	2	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée OBD capteur vitesse véhicule	7	0	1	0	1	0	1		0=OK¦1=Panne
panne mémorisée OBD sortie chauffage sonde amont	19	3	1	0	1	0	1		0=OK¦1=Panne
FLGPVE3	9	0	8	0	1	0	1		
FLGPVE2	6	0	8	0	1	0	1		
FLGPVE1	3	0	8	0	1	0	1		
panne mémorisée OBD sortie purge canister	19	2	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée OBD sortie EGR	19	0	1	0	1	0	1		0=OK¦1=Panne
allu MIL sur Panne OBD sortie  relais pompe à air	17	2	1	0	1	0	1		0=non demandé¦1=DEMANDE
Panne mémorisée OBD sur la liaison BVA	7	6	1	0	1	0	1		0=OK¦1=Panne
Panne mémorisée OBD sorties allumage 2 et 3	13	5	1	0	1	0	1		0=OK¦1=Panne
Panne validée OBD du capteur température air	3	5	1	0	1	0	1		0=OK¦1=Panne
Panne validée OBD sur la liaison BVA	6	6	1	0	1	0	1		0=OK¦1=Panne
Panne validée OBD du capteur température eau	3	4	1	0	1	0	1		0=OK¦1=Panne
allum MIL sur panne sortie chauffage sonde aval	20	5	1	0	1	0	1		0=non demandé¦1=DEMANDE
"""
    private const val FC6 = """
Compteur bonnes prédictions phasage arbre à cames	3	0	16	0	1	0	1		
compteur d échecs du détrompage cylindre	11	0	16	0	1	0	1		
Compteur de non décision du détrompage cylindre	5	0	16	0	1	0	1		
compteur de succés du détrompage cylindre	9	0	16	0	1	0	1		
Compteur mauvaises prédictions phasage arbre à cames	7	0	16	0	1	0	1		
"""
    private const val FC7 = """
Compteur succés recocylindre1 par détrompage	7	0	16	0	1	0	1		
Déchéance de couple pour recocylindre1	13	0	16	0	1	0	1	Nm	
Compteur succés recocylindre1 par détrompage RR	15	0	16	0	1	0	1		
Compteur échecs recocylindre1 par détrompage	9	0	16	0	1	0	1		
Compteur bonnes prédiction phas arbre à cames	3	0	16	0	1	0	1		
Compteur non décision recocylindre1 par détrompage RR	19	0	16	0	1	0	1		
Compteur échecs recocylindre1 par détrompage RR	17	0	16	0	1	0	1		
Compteur non décision recocylindre1 par détrompage	11	0	16	0	1	0	1		
Compteur mauvaises prédic phas arbre à cames	5	0	16	0	1	0	1		
"""

    val FRAME_TITLES: Map<Int, String> = mapOf(
        0x80 to "Identification",
        0xA0 to "Trame 01 - Pannes Entrées",
        0xA1 to "Trame 02 - Pannes Sorties 1",
        0xA2 to "Trame 03 - Entrées 1",
        0xA3 to "Trame 04 - Entrées 2",
        0xA4 to "Trame 05 - Sorties 1",
        0xA5 to "Trame 06 - Sorties 2",
        0xA6 to "Trame 07 - Sorties 3",
        0xA7 to "Trame 08 - Sorties 4",
        0xA8 to "Trame 09 - Parametres 1",
        0xA9 to "Trame 10 - Parametres 2",
        0xAA to "Trame 11 - Parametres 3",
        0xAB to "Trame 12 - Parametres 4",
        0xAC to "Trame 13 - Pannes Sorties 2",
        0xAD to "Trame 14 - Diagnostic Obd 1",
        0xAE to "Trame 15 - Configuration",
        0xAF to "Trame 16 - Diagnostic Obd 2",
        0xC6 to "Trame 39",
        0xC7 to "Trame 40",
    )

    val FRAME_ITEMS_TEXT: Map<Int, String> = mapOf(
        0x80 to F80,
        0xA0 to FA0,
        0xA1 to FA1,
        0xA2 to FA2,
        0xA3 to FA3,
        0xA4 to FA4,
        0xA5 to FA5,
        0xA6 to FA6,
        0xA7 to FA7,
        0xA8 to FA8,
        0xA9 to FA9,
        0xAA to FAA,
        0xAB to FAB,
        0xAC to FAC,
        0xAD to FAD,
        0xAE to FAE,
        0xAF to FAF,
        0xC6 to FC6,
        0xC7 to FC7,
    )

    /** Cada linha: nome \u0001 condição1 \t condição2 ... (condição = item=valor). */
    const val FAULTS_TEXT = "Capteur signal dent / Cible volant moteur\u0001Panne mémorisée OBD absence signal dent=1\tPanne présente défault cible volant moteur=1\tPanne validée OBD défaut cible volant moteur=1\tallum MIL sur Panne OBD défaut cible volant moteur=0\tPanne  présente absence de signal dent=1\tPanne mémorisée OBD défaut cible volant moteur=1\tPanne  mémorisée absence de signal dent=1\tallum MIL sur Panne OBD absence signal dent=0\tPanne validée OBD absence signal dent=1\tPanne mémorisée défault cible volant moteur=1\nCapteur position papillon\u0001Panne validée OBD du potetiomètre papillon=1\tPosition mesurée du potentiomètre papillon=0\tallumage MIL Panne  OBD potentiomètre papillon=0\tPanne mémorisée du potentiomètre papillon=1\tPanne présente du potentiomètre papillon=1\tPanne mémorisée OBD potentiomètre papillon=1\nCapteur cliquetis\u0001allumage MIL Panne  OBD capteur de cliquetis=0\tPanne mémorisée du capteur de cliquetis=1\tPanne présente du capteur de cliquetis=1\tPanne mémorisée OBD capteur de cliquetis=1\tPanne validée OBD du capteur de cliquetis=1\nOBD : Misfire Catalyseur\u0001Panne mémorisée OBD misfire catalyseur=1\tPanne présente OBD misfire catalyseur=1\tdemande allum MIL sur Panne  OBD misfire catalyseur=0\tPanne validée OBD misfire catalyseur=1\nCommande purge canister\u0001panne validée OBD purge canister=1\tSortie purge canister en CC au +bat=1\tallum MIL sur panne sortie purge canister=0\tRCO vanne purge canister=0\tpanne mémorisée OBD sortie purge canister=1\tSortie purge canister en circuit ouvert=1\tPanne présente purge Canister=1\tPanne présente sortie purge canister à la MsT=1\tPanne mémorisée purge Canister=1\tSortie purge canister en CC à la masse=1\nOBD : Misfire Pollution\u0001Panne présente OBD misfire pollution=1\tPanne validée OBD misfire pollution=1\tdemande MIL sur Panne OBD misfire pollution=0\tPanne mémorisée OBD misfire pollution=1\nCapteur pression collecteur\u0001Panne mémorisée OBD capteur pression collecteur=1\tPanne validée OBD du capteur pression collecteur=1\tPression collecteur absolue mesurée=0\tPanne mémorisée du capteur de pression absolue collecteur=1\tPanne présente du capteur de pression absolue collecteur=1\tPanne présente observation évolution de pression=1\tallum MIL Panne OBD capteur pression absolue=0\tPanne mémorisée observation évolution de pression=1\nAlimentation : Entrée + Actuateurs\u0001Panne présente sur alimentation VBR=1\tPanne mémorisée sur alimentation VBR=1\tTension batterie mesurée=0\nCTPH\u0001CTPH cut state=0\tConfiguration CTP habitacle=0\tState of additional heater relay 1=0\tPanne présente commande CTPH=1\tState of additional heater relay 3=0\tState of additional heater relay 2=0\tCTPH freeze state=0\tSortie commande CTPH en circuit ouvert=1\tSortie commande CTPH en court-circuit au +bat=1\tPanne mémorisée commande CTPH=1\tSortie commande CTPH en court-circuit à la masse=1\nCapteur température air\u0001Température air mesurée=0\tPanne mémorisée du capteur de température d air=1\tallum MIL Panne OBD capteur température air=0\tPanne validée OBD du capteur température air=1\tPanne présente du capteur de tempéature d air=1\tPanne mémorisée OBD capteur température air=1\nAlimentation : Entrée + Après contact\u0001Panne mémorisée sur alimentation +APC=1\tPanne présente sur alimentation +APC=1\nMasse moteur sonde 1/3 fil\u0001Panne présente acquisition masse moteur avec sonde type 1 fil=1\tPanne mémorisée acquisition masse moteur avec sonde type 1 fil=1\nClimatisation : Capteur pression\u0001Panne mémorisée pression climatiation=1\tPression du circuit de climatisation=0\tPanne présente pression climatisation=1\nCommande chauffage sonde aval\u0001Sortie chauffage sonde aval en CC au +bat=1\tPanne présente sortie chauffage sonde aval à MsT=1\tpanne validée OBD chauffage de la sonde aval=1\tSortie chauffage sonde aval en circuit ouvert=1\tSortie chauffage sonde aval en CC à la masse=1\tpanne mémo OBD sortie chauffage sonde aval=1\tPanne mémorisée chauffage sonde Aval=1\tPanne présente chauffage sonde Aval=1\tallum MIL sur panne sortie chauffage sonde aval=0\nFin de diag\u0001Stopok=0\nCommande chauffage sonde aval : Puissance\u0001panne mémo OBD sortie puissance sur chauffage sonde aval=1\tPanne mémorisée puissance chauffage O2 Aval=1\tpanne validée OBD de puissance sur chauffage sonde aval=1\tPanne présente puissance chauffage O2 Aval=1\tallumage MIL sur panne puissance chauffage sonde aval=0\nCommande décaleur arbre à cames\u0001Panne présente sortie décaleur arbre à came à la MsT=1\tPanne mémorisée sortie décaleur arbre à came=1\tPanne présente sortie décaleur arbre à came=1\tSortie décaleur arbre à came en CC au +bat=1\tSortie décaleur arbre à came en CC à la masse=1\tSortie décaleur arbre à came en circuit ouvert=1\nCommande voyant alerte température eau\u0001Panne mémorisée voyant alerte température eau=1\tSortie voyant alerte eau en CC à la masse=1\tSortie voyant alerte eau en circuit ouvert=1\tSortie voyant alerte eau en CC au +bat=1\tPanne présente sortie voyant alerte eau à la MsT=1\tPanne présente voyant alerte température eau=1\nOBD : Fuel System\u0001Panne présente OBD fuel system=1\tdemande allumMIL sur Panne  OBD fuel system=0\tPanne mémorisée OBD fuel system=1\tPanne validée OBD fuel system=1\nCommande chauffage sonde amont : Puissance\u0001allum MIL sur panne puissance chauffage sonde amont=0\tPanne mémorisée puissance chauffage O2 Amont=1\tPanne présente puissance chauffage O2 Amont=1\tpanne mémo OBD sortie puissance sur chauffage sonde amont=1\tpanne validée OBD de puissance sur chauffage sonde amont=1\nCommande chauffage sonde amont\u0001Sortie chauffage sonde amont en CC à la masse=1\tallum MIL sur panne sortie chauffage sonde amont=0\tPanne présente chauffage sonde Amont=1\tSortie chauffage sonde amont en CC au +bat=1\tpanne mémorisée OBD sortie chauffage sonde amont=1\tPanne présente sortie chauffage sonde amont à la MsT=1\tpanne validée OBD chauffage de la sonde amont=1\tPanne mémorisée chauffage sonde Amont=1\tSortie chauffage sonde amont en circuit ouvert=1\nGPL : Liaison inter-système\u0001Panne présente liaison gpl-inj=1\tPanne mémorisée liaison gpl-inj=1\nCommande allumage 1 et 4\u0001Panne présente sorties allumage 1 et 4 à la MsT.=1\tPanne présente sorties allumage 1 et 4.=1\tSorties allumage 1 et 4 en CC à la masse.=1\tSorties allumage 1 et 4 en CC au +bat.=1\tSorties allumage 1 et 4 en circuit ouvert=1\tPanne mémorisée sorties allumage 1 et 4 .=1\tPanne validée OBD sorties allumage 1 et 4=1\tPanne mémorisée OBD sorties allumage 1 et 4=1\tdemande allum MIL sur panne OBD sorties allumage 1 et 4=0\nClimatisation : Liaison inter-système\u0001Panne mémorisée sur la liaison clim - injection=1\tPanne présente sur la liaison clim - injection=1\nCapteur température eau\u0001Panne mémorisée du capteur de température d eau=1\tTempérature eau mesurée=0\tPanne validée OBD du capteur température eau=1\tallum MIL Panne OBD capteur température eau=0\tPanne présente du capteur de température d eau=1\tPanne mémorisée OBD capteur température eau=1\nSonde aval\u0001allum MIL sur Panne OBD sonde à oxygène aval=0\tPanne présente sonde à oxygène aval=1\tPanne validée OBD sonde à oxygène aval=1\tPanne mémorisée sonde à oxygène aval=1\tPanne mémorisée OBD sonde à oxygène aval=1\tTension de la sonde à oxygène aval=0\nBVA : Liaison multiplexée\u0001Demande allumage MIL par la BVA=0\tPanne présente sur la liaison BVA=1\tallum MIL sur Panne  OBD sur la liaison BVA=0\tPanne mémorisée sur la liaison BVA=1\tPanne validée OBD sur la liaison BVA=1\tPanne mémorisée OBD sur la liaison BVA=1\nCapteur vitesse véhicule\u0001Panne mémorisée du capteur vitesse véhicule=1\tPanne mémorisée OBD capteur vitesse véhicule=1\tPanne présente du capteur de vitesse véhicule=1\tallum MIL sur Panne OBD capteur vitesse véhicule=0\tVitesse du véhicule=0\tPanne validée OBD du capteur de vitesse véhicule=1\nSonde amont\u0001Panne validée OBD sonde à oxygène amont=1\tPanne mémorisée sonde à oxygène amont=1\tTension de la sonde à oxygène amont=0\tPanne présente sonde à oxygène amont=1\tPanne mémorisée OBD sonde à oxygène amont=1\tallum MIL Panne OBD sonde à oxygène amont=0\nCalculateur\u0001Panne calculateur injection=1\tPanne CKS global de la mémoire de sauvegarde=1\tPanne zone verrou logiciel de la sauvegarde=1\nCommande allumage 2 et 3\u0001Sorties allumage 2 et 3 en CC à la masse.=1\tdemande allum MIL sur panne OBD sorties allumage 2 et 3=0\tPanne présente sorties allumage 2 et 3 à la MsT.=1\tPanne mémorisée OBD sorties allumage 2 et 3=1\tPanne présente sorties allumage 2 et 3.=1\tSorties allumage 2 et 3 en circuit ouvert=1\tSorties allumage 2 et 3 en CC au +bat.=1\tPanne validée OBD sorties allumage 2 et 3=1\tPanne mémorisée sorties allumage 2 et 3 .=1\nABS : Liaison multiplexée\u0001Panne mémorisée sur la liaison ABS=1\tPanne présente sur la liaison ABS=1\nPannes génériques Entrées/ Sorties\u0001Pannes présentes sur une des sorties=1\tPannes présentes sur une des entrées=1\tPannes mémorisées sur une des sorties=1\tPannes mémorisées sur une des entrées=1\nCommande voyant MIL\u0001panne validée OBD voyant MIL=1\tSortie voyant MIL en CC à la masse=1\tSortie voyant MIL en circuit ouvert=1\tPanne mémorisée voyant MIL=1\tPanne présente voyant MIL à la MsT=1\tPanne présente voyant MIL=1\tSortie voyant MIL en CC au +bat=1\tpanne mémorisée OBD sortie voyant MIL=1\nAlternator Borne DF\u0001Configuation de l'utilisation de la borne DF au ralenti=0\tPanne présente Borne DF alternateur=1\tPanne mémorisée Borne DF alternateur=1\nVerrou logiciel : Liaison inter-système\u0001Panne présente sur la liaison codée=1\tPanne mémorisée sur la liaison codée=1\nOBD : Sonde amont\u0001Panne présente OBD sondes à oxygène=1\tdemande MIL sur Panne OBD sondes à oxygène=0\tPanne validée OBD sondes à oxygène=1\tPanne mémorisée OBD sondes à oxygène=1\nCommande relais pompe à essence\u0001Panne mémorisée relais de pompe à essence=1\tPanne mémorisée OBD sortie pompe à essence=1\tPanne présente relais de pompe à essence=1\tPanne présente sortie pompe essence à la MsT=1\tSortie relais pompe à essence en CC au +bat=1\tPanne validée OBD relais pompe à essence=1\tallu MIL sur Panne  OBD sortie pompe à essence=0\tSortie relais pompe à essence en circuit ouvert=1\tSortie relais pompe à essence en CC à la masse=1\nCommande régulateur de ralenti : Stepper\u0001Panne mémorisée EOBD régulation ralenti=1\tDemande allumage MIL sur panne EOBD régulation ralenti=0\tSortie régulation ralenti en circuit ouvert=1\tPanne mémorisée régulation ralenti=1\tSortie régulation ralenti en CC à la masse=1\tPanne présente sortie régulation ralenti à la MsT=1\tRCO théorique régulation ralenti=0\tPanne présente régulation ralenti=1\tSortie régulation ralenti en CC au +bat=1\tPanne validée régulation ralenti=1\nOBD : Catalyseur\u0001Panne mémorisée OBD catalyseur=1\tdemande allumage MIL Panne OBD catalyseur=0\tPanne validée OBD catalyseur=1\tPanne présente OBD catalyseur=1\nCommande relais actuateur\u0001Sortie relais actuateurs en CC à la masse=1\tPanne présente relais actuateur=1\tSortie relais actuateurs en CC au +bat=1\tPanne mémorisée relais actuateur=1\tPanne présente relais actuateur à la MsT=1\tSortie relais actuateurs en circuit ouvert=1\nCommande injecteur 1\u0001Panne présente Injecteur 1=1\tInjecteur 1 en CC à la masse=1\tPanne validée OBD injecteur 1=1\tInjecteur 1 en circuit ouvert=1\tInjecteur 1 en CC au +bat=1\tPanne mémorisée Injecteur 1=1\tPanne présente injecteur 1 à la MsT=1\tdemande allum MIL sur Panne  OBD injecteur 1=0\tPanne mémorisée OBD injecteur 1=1\nCommande injecteur 3\u0001Panne présente Injecteur 3=1\tPanne présente injecteur 3 à la MsT=1\tInjecteur 3 en CC à la masse=1\tInjecteur 3 en CC au +bat=1\tInjecteur 3 en circuit ouvert=1\tPanne mémorisée Injecteur 3=1\tPanne validée OBD injecteur 3=1\tdemande allum MIL sur Panne OBD injecteur 3=0\tPanne mémorisée OBD injecteur 3=1\nCommande injecteur 2\u0001Panne présente Injecteur 2=1\tPanne validée OBD injecteur 2=1\tPanne mémorisée Injecteur 2=1\tPanne mémorisée OBD injecteur 2=1\tPanne présente injecteur 2 à la MsT=1\tdemande allum MIL sur Panne OBD injecteur 2=0\tInjecteur 2 en circuit ouvert=1\tInjecteur 2 en CC à la masse=1\tInjecteur 2 en CC au +bat=1\nCommande injecteur 4\u0001Panne validée OBD injecteur 4=1\tPanne présente Injecteur 4=1\tPanne mémorisée Injecteur 4=1\tInjecteur 4 en CC au +bat=1\tInjecteur 4 en CC à la masse=1\tInjecteur 4 en circuit ouvert=1\tPanne mémorisée OBD injecteur 4=1\tdemande allum MIL sur Panne OBD injecteur 4=0\tPanne présente injecteur 4 à la MsT=1"
}
