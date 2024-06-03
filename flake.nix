{
  inputs = {
    flake-utils.url = "github:numtide/flake-utils";
    plutus.url = "github:input-output-hk/plutus/e2cbee0d31da1b2dfa42cc76fb112dc69fa06798";
  };

  outputs =
    { self
    , flake-utils
    , nixpkgs
    # , cardano-node
    , plutus
    , ...
    } @ inputs:
    (flake-utils.lib.eachSystem [ "x86_64-darwin" "x86_64-linux" ]
      (system:
      let
        pkgs = import nixpkgs { inherit system; };
        uplc = plutus.${system}.plutus.library.plutus-project.hsPkgs.plutus-core.components.exes.uplc;
      in
      rec {
        devShell = pkgs.mkShell {
          JAVA_OPTS="-Xmx2g -XX:+UseG1GC";
          # This fixes bash prompt/autocomplete issues with subshells (i.e. in VSCode) under `nix develop`/direnv
          buildInputs = [ pkgs.bashInteractive ];
          packages = with pkgs; [
            git
            openjdk11
            sbt
            scalafmt
            niv
            nixpkgs-fmt
            nodejs
            uplc
          ];
        };
      })
    );

  nixConfig = {
    extra-substituters = [
      "https://cache.iog.io"
    ];
    extra-trusted-public-keys = [
      "hydra.iohk.io:f/Ea+s+dFdN+3Y/G+FDgSq+a5NEWhJGzdjvKNGv0/EQ="
    ];
    allow-import-from-derivation = true;
  };
}
