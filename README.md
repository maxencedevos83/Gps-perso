# GPS Perso

GPS Android personnel, sans publicité : carte et guidage façon Google Maps, alertes façon Waze.

## Installer l'appli

1. Sur le téléphone, ouvrir la page **Releases** du dépôt et télécharger `gps-perso.apk` de la dernière version.
2. Ouvrir le fichier et autoriser l'installation depuis cette source si Android le demande.
3. Chaque nouvelle version s'installe par-dessus la précédente (même clé de signature).

L'APK est compilée automatiquement par GitHub Actions à chaque modification de `main`.

## Fonctions

- Carte OpenStreetMap (tuiles OpenFreeMap, gratuites, sans clé).
- Recherche d'adresse (Photon) ou appui long sur la carte pour choisir une destination.
- Itinéraire voiture (Valhalla), durée, distance et heure d'arrivée.
- Guidage vocal en français, bandeau de manœuvre, recalcul automatique en cas d'écart.
- Radars fixes issus d'OpenStreetMap, annoncés à l'approche (avec ou sans itinéraire).
- Bouton ⚠ pour signaler police, radar mobile, accident, danger, travaux, bouchon (gardés sur le téléphone, expirent automatiquement).
- Bouton « Waze » pour ouvrir la même destination dans Waze.

## Limites connues

- Pas encore de trafic en temps réel (prévu via l'offre gratuite TomTom).
- Les signalements ne sont visibles que sur ce téléphone.
- Le guidage s'arrête si l'appli passe en arrière-plan trop longtemps (garder l'écran allumé).
- Serveurs publics gratuits : usage personnel modéré uniquement.
