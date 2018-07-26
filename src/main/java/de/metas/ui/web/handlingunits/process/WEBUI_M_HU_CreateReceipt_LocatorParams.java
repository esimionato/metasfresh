package de.metas.ui.web.handlingunits.process;

import org.adempiere.util.Services;
import org.adempiere.warehouse.LocatorId;
import org.adempiere.warehouse.api.IWarehouseBL;
import org.adempiere.warehouse.api.IWarehouseDAO;
import org.compiere.model.I_M_Locator;
import org.springframework.context.annotation.Profile;

import com.google.common.collect.ImmutableList;

import de.metas.Profiles;
import de.metas.handlingunits.model.I_M_ReceiptSchedule;
import de.metas.handlingunits.model.I_M_Warehouse;
import de.metas.handlingunits.receiptschedule.IHUReceiptScheduleBL.CreateReceiptsParameters.CreateReceiptsParametersBuilder;
import de.metas.process.IProcessDefaultParameter;
import de.metas.process.IProcessDefaultParametersProvider;
import de.metas.process.IProcessParametersCallout;
import de.metas.process.IProcessPrecondition;
import de.metas.process.Param;
import de.metas.ui.web.process.descriptor.ProcessParamLookupValuesProvider;
import de.metas.ui.web.window.datatypes.LookupValue.IntegerLookupValue;
import de.metas.ui.web.window.datatypes.LookupValuesList;
import lombok.NonNull;

/*
 * #%L
 * metasfresh-webui-api
 * %%
 * Copyright (C) 2017 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */
@Profile(Profiles.PROFILE_Webui)
public class WEBUI_M_HU_CreateReceipt_LocatorParams
		extends WEBUI_M_HU_CreateReceipt_Base
		implements IProcessPrecondition,
		IProcessParametersCallout,
		IProcessDefaultParametersProvider
{

	private final transient IWarehouseBL warehouseBL = Services.get(IWarehouseBL.class);
	private final transient IWarehouseDAO warehouseDAO = Services.get(IWarehouseDAO.class);

	private final static String WAREHOUSE_PARAM_NAME = I_M_ReceiptSchedule.COLUMNNAME_M_Warehouse_Dest_ID;
	@Param(mandatory = false, parameterName = WAREHOUSE_PARAM_NAME)
	private I_M_Warehouse destinationWarehouse;

	private final static String LOCATOR_PARAM_NAME = "M_Locator_Dest_ID";
	@Param(mandatory = false, parameterName = LOCATOR_PARAM_NAME)
	private I_M_Locator destinationLocator;

	@Override
	public Object getParameterDefaultValue(@NonNull final IProcessDefaultParameter parameter)
	{
		if (WAREHOUSE_PARAM_NAME.equals(parameter.getColumnName()))
		{
			final ImmutableList<Integer> distinctWarehouseIds = getM_ReceiptSchedules()
					.stream()
					.map(I_M_ReceiptSchedule::getM_Warehouse_Dest_ID)
					.distinct()
					.collect(ImmutableList.toImmutableList());
			if (distinctWarehouseIds.size() == 1)
			{
				return distinctWarehouseIds.get(0);
			}
		}

		else if (LOCATOR_PARAM_NAME.equals(parameter.getColumnName()))
		{
			if (destinationWarehouse != null)
			{
				final I_M_Locator defaultLocator = warehouseBL.getDefaultLocator(destinationWarehouse);
				if (defaultLocator != null)
				{
					return defaultLocator.getM_Locator_ID();
				}
			}
		}
		return null;
	}

	@Override
	public void onParameterChanged(String parameterName)
	{
		if (!WAREHOUSE_PARAM_NAME.equals(parameterName))
		{
			return;
		}
		if (destinationWarehouse == null)
		{
			destinationLocator = null;
			return;
		}

		destinationLocator = warehouseBL.getDefaultLocator(destinationWarehouse);
	}

	@ProcessParamLookupValuesProvider(parameterName = LOCATOR_PARAM_NAME, dependsOn = WAREHOUSE_PARAM_NAME, numericKey = true)
	public LookupValuesList getLocators()
	{
		if (destinationWarehouse == null)
		{
			return LookupValuesList.EMPTY;
		}

		return warehouseDAO
				.retrieveLocators(destinationWarehouse)
				.stream()
				.map(locator -> IntegerLookupValue.of(locator.getM_Locator_ID(), locator.getValue()))
				.collect(LookupValuesList.collect());
	}

	@Override
	protected void customizeParametersBuilder(@NonNull final CreateReceiptsParametersBuilder parametersBuilder)
	{
		final LocatorId locatorId = LocatorId.ofRecordOrNull(destinationLocator);
		parametersBuilder.destinationLocatorId(locatorId);
	}
}
