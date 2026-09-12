package io.github.oliviercailloux.git.factory;

import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;

record MEntry (FileMode mode, ObjectId oid) {
}
