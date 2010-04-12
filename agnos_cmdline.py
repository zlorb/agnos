#!/usr/bin/env python
from agnos.compiler import compile, IDLError, JavaTarget, PythonTarget


if __name__ == "__main__":
    try:
        compile("../ut/RemoteFiles.xml", PythonTarget("gen-py"))
    except IDLError:
        raise
    except Exception:
        import sys
        import pdb
        import traceback
        traceback.print_exception(*sys.exc_info())
        pdb.post_mortem(sys.exc_info()[2])



