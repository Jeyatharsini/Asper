/*
 * *************************************************************************************
 *  Copyright (C) 2008 EsperTech, Inc. All rights reserved.                            *
 *  http://esper.codehaus.org                                                          *
 *  http://www.espertech.com                                                           *
 *  ---------------------------------------------------------------------------------- *
 *  The software in this package is published under the terms of the GPL license       *
 *  a copy of which has been included with this distribution in the license.txt file.  *
 * *************************************************************************************
 */

package com.espertech.esper.core.context.mgr;

import com.espertech.esper.filter.FilterHandle;
import com.espertech.esper.filter.FilterSpecCompiled;
import com.espertech.esper.filter.FilterValueSetParam;

import java.util.IdentityHashMap;
import java.util.List;

public class AgentInstanceFilterProxyImpl implements AgentInstanceFilterProxy {

    private final IdentityHashMap<FilterSpecCompiled, List<FilterValueSetParam>> addendums;

    public AgentInstanceFilterProxyImpl(IdentityHashMap<FilterSpecCompiled, List<FilterValueSetParam>> addendums) {
        this.addendums = addendums;
    }

    public List<FilterValueSetParam> getAddendumFilters(FilterSpecCompiled filterSpec) {
        return addendums.get(filterSpec);
    }
}
