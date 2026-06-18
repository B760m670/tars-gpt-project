"""TARS terminal: run shell commands and Python inside the app's sandbox.

Phase 1 is a manual terminal — the human types a command and TARS runs it. The
same engine will back the agentic mode later (TARS issuing its own commands).

Two modes from one prompt:
  - "py <code>"  -> run Python in a persistent namespace (REPL-like)
  - anything else -> run as a shell command via `sh -c`

Everything stays within the OS sandbox the app is granted; there is no root.
A session keeps its working directory and Python namespace between commands.
"""
from __future__ import annotations

import io
import os
import subprocess
import sys
import traceback

_SHELL_TIMEOUT = 30


class Terminal:
    def __init__(self, base_dir: str = "."):
        self.cwd = os.path.abspath(base_dir)
        self.ns: dict = {"__name__": "tars_terminal"}

    def run(self, line: str) -> str:
        line = (line or "").strip()
        if not line:
            return ""
        if line == "py" or line.startswith("py "):
            return self._python(line[2:].strip())
        if line.startswith(":py "):
            return self._python(line[4:].strip())
        if line == "cd" or line.startswith("cd ") or line.startswith("cd\t"):
            return self._cd(line[2:].strip())
        if line in ("pwd",):
            return self.cwd
        return self._shell(line)

    def _cd(self, target: str) -> str:
        target = target or os.path.expanduser("~")
        path = target if os.path.isabs(target) else os.path.join(self.cwd, target)
        path = os.path.abspath(path)
        if not os.path.isdir(path):
            return "cd: no such directory: {}".format(target)
        self.cwd = path
        return self.cwd

    def _shell(self, cmd: str) -> str:
        try:
            p = subprocess.run(
                ["sh", "-c", cmd],
                cwd=self.cwd,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                timeout=_SHELL_TIMEOUT,
            )
            out = p.stdout.decode("utf-8", "replace")
            if not out.strip():
                out = "(no output, exit {})".format(p.returncode)
            return out
        except subprocess.TimeoutExpired as e:
            partial = (e.output or b"").decode("utf-8", "replace")
            return partial + "\n(timed out after {}s)".format(_SHELL_TIMEOUT)
        except FileNotFoundError:
            return "error: no shell available on this device"
        except Exception as e:  # pragma: no cover - defensive
            return "error: {}".format(e)

    def _python(self, code: str) -> str:
        if not code:
            return "(usage: py <python code>)"
        buf = io.StringIO()
        saved = sys.stdout, sys.stderr
        sys.stdout = sys.stderr = buf
        try:
            try:
                # Expression? echo its repr, like a REPL.
                result = eval(compile(code, "<terminal>", "eval"), self.ns)
                if result is not None:
                    print(repr(result))
            except SyntaxError:
                exec(compile(code, "<terminal>", "exec"), self.ns)
        except Exception:
            traceback.print_exc()
        finally:
            sys.stdout, sys.stderr = saved
        return buf.getvalue() or "(ok)"
