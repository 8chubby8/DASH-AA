# Programs the user installs for themselves (Claude Code) live in ~/.local/bin, on the user partition.
case ":$PATH:" in *":$HOME/.local/bin:"*) ;; *) PATH="$HOME/.local/bin:$PATH" ;; esac
