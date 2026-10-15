package com.faforever.iceadapter.util;

import java.util.Collection;
import lombok.experimental.UtilityClass;

@UtilityClass
public class CollectionUtils {

    public boolean isEmpty(Collection<?> collection) {
        return collection == null || collection.isEmpty();
    }
}
