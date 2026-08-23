# Third-party notices — `word-graph`

This package ships third-party data and a third-party renderer. Both notices below
are reproduced in full and **must travel with these assets**: anything that bundles
`word-graph/assets` into a shipped app has to reproduce them, and should surface
them in its own "Licenses & Attribution" screen.

---

## Princeton WordNet 3.0

`assets/graph/nodes.tsv`, `assets/graph/edges.tsv` and `assets/graph/words.tsv` are
derived from Princeton WordNet 3.0 — synsets and the relation pointers between them,
extracted into a compact tab-separated form for the graph renderer.

Reformatting does not discharge the licence's notice obligation, it inherits it, so
the notice below is reproduced verbatim and in full, as the licence requires of
"ALL copies … including modifications".

Note the no-publicity clause: this notice is required, and it is *not* permission to
use Princeton's name in store listings or marketing copy.

```
WordNet Release 3.0

This software and database is being provided to you, the LICENSEE, by
Princeton University under the following license.  By obtaining, using
and/or copying this software and database, you agree that you have
read, understood, and will comply with these terms and conditions.:

Permission to use, copy, modify and distribute this software and
database and its documentation for any purpose and without fee or
royalty is hereby granted, provided that you agree to comply with
the following copyright notice and statements, including the disclaimer,
and that the same appear on ALL copies of the software, database and
documentation, including modifications that you make for internal
use or for distribution.

WordNet 3.0 Copyright 2006 by Princeton University.  All rights reserved.

THIS SOFTWARE AND DATABASE IS PROVIDED "AS IS" AND PRINCETON
UNIVERSITY MAKES NO REPRESENTATIONS OR WARRANTIES, EXPRESS OR
IMPLIED.  BY WAY OF EXAMPLE, BUT NOT LIMITATION, PRINCETON
UNIVERSITY MAKES NO REPRESENTATIONS OR WARRANTIES OF MERCHANT-
ABILITY OR FITNESS FOR ANY PARTICULAR PURPOSE OR THAT THE USE
OF THE LICENSED SOFTWARE, DATABASE OR DOCUMENTATION WILL NOT
INFRINGE ANY THIRD PARTY PATENTS, COPYRIGHTS, TRADEMARKS OR
OTHER RIGHTS.

The name of Princeton University or Princeton may not be used in
advertising or publicity pertaining to distribution of the software
and/or database.  Title to copyright in this software, database and
any associated documentation shall at all times remain with
Princeton University and LICENSEE agrees to preserve same.
```

---

## AntV G6

`assets/vendor/g6.min.js` is [AntV G6](https://g6.antv.antgroup.com/) v5.1.1,
vendored as a local asset so the graph makes no network request to draw itself.
G6 is distributed under the MIT License:

```
MIT License

Copyright (c) 2018 Alipay.inc

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## Not covered here

The gloss text a host serves over `dict.wordgraph.local` is the **host's own**
corpus and carries whatever licence that corpus carries — this package neither
ships nor licenses it. If it is also WordNet-derived, the notice above applies to
it too.
