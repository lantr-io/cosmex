{
  inputs = {
    nixpkgs.follows = "haskellNix/nixpkgs";
    haskellNix.url = "github:input-output-hk/haskell.nix";
    iohk-nix.url = "github:input-output-hk/iohk-nix";
    flake-utils.url = "github:numtide/flake-utils";
    CHaP = {
      url = "github:input-output-hk/cardano-haskell-packages?ref=repo";
      flake = false;
    };
    cardano-node.url = "github:input-output-hk/cardano-node/1.35.7";
    plutus.url = "github:input-output-hk/plutus/e2cbee0d31da1b2dfa42cc76fb112dc69fa06798";
    /* gitignore-nix = {
      url = "github:hercules-ci/gitignore.nix";
      inputs.nixpkgs.follows = "nixpkgs";
    }; */
  };

  outputs =
    { self
    , flake-utils
    , nixpkgs
    , cardano-node
    , plutus
    , ...
    } @ inputs:
    (flake-utils.lib.eachSystem [ "x86_64-darwin" "x86_64-linux" ]
      (system:
      let
        pkgs = import nixpkgs { inherit system; };
      in
      rec {
        devShell = pkgs.mkShell {
          # This fixes bash prompt/autocomplete issues with subshells (i.e. in VSCode) under `nix develop`/direnv
          buildInputs = [ pkgs.bashInteractive ];
          packages = with pkgs; [
            git
            openjdk11
            sbt
            scalafmt
            niv
            nixpkgs-fmt
            # (builtins.trace (builtins.toJSON plutus) nodejs)
            nodejs
            cardano-node.packages.${system}.cardano-node
            cardano-node.packages.${system}.cardano-cli
            plutus.${system}.plutus.library.plutus-project.hsPkgs.plutus-core.components.exes.uplc
          ];
        };
      })
    );

  nixConfig = {
    extra-substituters = [
      "https://cache.iog.io"
      "https://hydra-node.cachix.org"
      "https://cache.zw3rk.com"
    ];
    extra-trusted-public-keys = [
      "hydra.iohk.io:f/Ea+s+dFdN+3Y/G+FDgSq+a5NEWhJGzdjvKNGv0/EQ="
      "hydra-node.cachix.org-1:vK4mOEQDQKl9FTbq76NjOuNaRD4pZLxi1yri31HHmIw="
      "loony-tools:pr9m4BkM/5/eSTZlkQyRt57Jz7OMBxNSUiMC4FkcNfk="
    ];
    allow-import-from-derivation = true;
  };
}
