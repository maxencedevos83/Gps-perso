# GPS Perso

GPS Android personnel, sans publicité : carte et guidage façon Google Maps, alertes façon Waze.

## Installer l'appli

1. Sur le téléphone, ouvrir la page **Releases** du dépôt et télécharger `gps-perso.apk` de la dernière version.
2. Ouvrir le fichier et autoriser l'installation depuis cette source si Android le demande.
3. Chaque nouvelle version s'installe par-dessus la précédente (même clé de signature).

L'APK est compilée automatiquement par GitHub Actions à chaque modification de `main`.

## Fonctions

- Carte OpenStreetMap (tuiles OpenFreeMap, gratuites, sans clé), affichage plein écran.
- Recherche d'adresse (Photon) et de POIs perso, ou appui long sur la carte.
- Itinéraire voiture (Valhalla), durée, distance et heure d'arrivée.
- Guidage vocal en français, bandeau de manœuvre, recalcul automatique en cas d'écart.
- Trafic en temps réel TomTom (bouton 🚦) : couleurs de fluidité, incidents annoncés (accidents, bouchons, travaux, routes fermées) et heure d'arrivée corrigée par les bouchons.
- Radars : import d'un fichier Lufop (CSV, GPX, KML, OV2 ou ZIP, menu ☰ › Importer) avec liste des nouveautés à chaque nouvel import ; à défaut, radars OpenStreetMap.
- Toucher un lieu de la carte (station, resto, commerce…) ouvre sa fiche avec « Y aller » ; pour une station, les prix s'affichent en grand.
- Bornes de recharge OpenStreetMap autour de soi et de la destination.
- POIs perso : appui long sur la carte ou menu ☰ › Ajouter ma position, avec catégorie.
- Bouton ⚠ pour signaler police, radar mobile, accident, danger, travaux, bouchon.
- Bouton « Waze » pour ouvrir la même destination dans Waze.
- Agenda du téléphone (Google Agenda, etc.) : rendez-vous avec un lieu, raccourci « Y aller » pour le prochain.
- Icône de position au choix : flèche, voitures, moto, camion (☰ › Réglages).
- Stations-service (bouton ⛽) avec prix officiels du carburant choisi (data.economie.gouv.fr), tri par prix.
- Restaurants et McDonald's / Burger King (bouton 🍔), ces derniers toujours mis en évidence sur la carte.
- Pendant le guidage, les recherches portent sur le trajet devant soi, avec « Faire un arrêt ici ».

## Clés gratuites (menu ☰ › Réglages)

- **TomTom** (trafic) : compte sur https://developer.tomtom.com, puis copier la clé API.

## Limites connues

- Pas de TMC : les téléphones Android n'ont pas accès au signal RDS-TMC de la radio FM.
- Les alertes Waze ne sont pas accessibles (aucune API publique).
- Les signalements perso ne sont visibles que sur ce téléphone.
- Le guidage s'arrête si l'appli passe en arrière-plan trop longtemps (garder l'écran allumé).
- Serveurs publics gratuits : usage personnel modéré uniquement.
