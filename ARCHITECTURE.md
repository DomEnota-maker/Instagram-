# MediaLoader Architecture

## Core idea

The application is designed as an extensible media downloader.

Instagram is the first provider. Future providers must be added without rewriting UI, history or download engine.

## Layers

- app: UI and navigation
- core: shared models and contracts
- providers: source-specific extraction logic
- downloader: common download engine
- database: local history and settings

## Provider rule

Each source has its own algorithms.

Example:

InstagramProvider -> Instagram extraction flow
YouTubeProvider -> YouTube extraction flow

They return common MediaItem objects.

## Current goal

Create a clean foundation before implementing extractors.
