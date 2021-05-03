package slimeknights.tconstruct.library.modifiers.capability;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import slimeknights.tconstruct.library.Util;
import slimeknights.tconstruct.library.modifiers.ModifierEntry;
import slimeknights.tconstruct.library.tools.nbt.IModifierToolStack;
import slimeknights.tconstruct.library.tools.nbt.ToolStack;

/**
 * Logic to make a tool a fluid handler
 */
@RequiredArgsConstructor
public class ToolFluidCapability implements IFluidHandlerItem {
  /** Boolean key to set in volatile mod data to enable the fluid capability */
  public static final ResourceLocation HAS_CAPABILITY = Util.getResource("has_fluid_capability");

  @Getter
  private final ItemStack container;
  private final ToolStack tool;
  public ToolFluidCapability(ItemStack stack) {
    this(stack, ToolStack.from(stack));
  }

  @Override
  public int getTanks() {
    int tanks = 0;
    for (ModifierEntry entry : tool.getModifierList()) {
      IFluidModifier fluidModifier = entry.getModifier().getModule(IFluidModifier.class);
      if (fluidModifier != null) {
        tanks += fluidModifier.getTanks(tool, entry.getLevel());
      }
    }
    return tanks;
  }

  /**
   * Runs a fluid handler function for a tank index
   * @param tank          Tank index
   * @param function      Function to run
   * @param defaultValue  Default value if none of the modifiers have the proper tank index
   * @param <T>  Return type
   * @return  Value from the modifiers
   */
  private <T> T runForTank(int tank, T defaultValue, ITankCallback<T> function) {
    for (ModifierEntry entry : tool.getModifierList()) {
      IFluidModifier fluidModifier = entry.getModifier().getModule(IFluidModifier.class);
      if (fluidModifier != null) {
        int currentTanks = fluidModifier.getTanks(tool, entry.getLevel());
        if (tank < currentTanks) {
          return function.run(fluidModifier, tool, entry.getLevel(), tank);
        }
        // subtract tanks in the current modifier, tank is 0 indexed from the modifier
        tank -= currentTanks;
      }
    }
    return defaultValue;
  }

  @Override
  public FluidStack getFluidInTank(int tank) {
    return runForTank(tank, FluidStack.EMPTY, IFluidModifier::getFluidInTank);
  }

  @Override
  public int getTankCapacity(int tank) {
    return runForTank(tank, 0, IFluidModifier::getTankCapacity);
  }

  @Override
  public boolean isFluidValid(int tank, FluidStack stack) {
    return runForTank(tank, false, (module, tool, level, tank1) -> module.isFluidValid(tool, level, tank1, stack));
  }

  @Override
  public int fill(FluidStack resource, FluidAction action) {
    int totalFilled = 0;
    for (ModifierEntry entry : tool.getModifierList()) {
      IFluidModifier fluidModifier = entry.getModifier().getModule(IFluidModifier.class);
      if (fluidModifier != null) {
        // try filling each modifier
        int filled = fluidModifier.fill(tool, entry.getLevel(), resource, action);
        if (filled > 0) {
          totalFilled += filled;
          // continue to the next modifier if we still have fluid left
          resource.shrink(filled);
          if (resource.isEmpty()) {
            return totalFilled;
          }
        }
      }
    }
    return totalFilled;
  }

  @Override
  public FluidStack drain(FluidStack resource, FluidAction action) {
    FluidStack drainedSoFar = FluidStack.EMPTY;
    for (ModifierEntry entry : tool.getModifierList()) {
      IFluidModifier fluidModifier = entry.getModifier().getModule(IFluidModifier.class);
      if (fluidModifier != null) {
        // try draining each modifier
        FluidStack drained = fluidModifier.drain(tool, entry.getLevel(), resource, action);
        if (!drained.isEmpty()) {
          // if we managed to drain something, add it into our current drained stack, and decrease the amount we still want to drain
          if (drainedSoFar.isEmpty()) {
            drainedSoFar = drained;
          } else {
            drainedSoFar.grow(drained.getAmount());
          }
          // if we drained everything desired, return
          resource.shrink(drained.getAmount());
          if (resource.isEmpty()) {
            return drainedSoFar;
          }
        }
      }
    }
    return drainedSoFar;
  }

  @Override
  public FluidStack drain(int maxDrain, FluidAction action) {
    FluidStack drainedSoFar = FluidStack.EMPTY;
    FluidStack toDrain = FluidStack.EMPTY;
    for (ModifierEntry entry : tool.getModifierList()) {
      IFluidModifier fluidModifier = entry.getModifier().getModule(IFluidModifier.class);
      if (fluidModifier != null) {
        // try draining each modifier
        // if we have no drained anything yet, use the type insensitive hook
        if (toDrain.isEmpty()) {
          FluidStack drained = fluidModifier.drain(tool, entry.getLevel(), maxDrain, action);
          if (!drained.isEmpty()) {
            // if we finished draining, we are done, otherwise try again later with the type senstive hooks
            maxDrain -= drained.getAmount();
            if (maxDrain > 0) {
              drainedSoFar = drained;
              toDrain = new FluidStack(drained, maxDrain);
            } else {
              return drained;
            }
          }
        } else {
          // if we already drained some fluid, type sensitive and increase our results
          FluidStack drained = fluidModifier.drain(tool, entry.getLevel(), toDrain, action);
          if (!drained.isEmpty()) {
            drainedSoFar.grow(drained.getAmount());
            toDrain.shrink(drained.getAmount());
            if (toDrain.isEmpty()) {
              return drainedSoFar;
            }
          }
        }
      }
    }
    return drainedSoFar;
  }

  /** Interface for modifiers with fluid capabilities to return */
  public interface IFluidModifier {
    /**
     * Determines how many fluid tanks are used by this modifier
     * @param tool   Tool instance
     * @param level  Modifier level
     * @return  Number of tanks used
     */
    default int getTanks(IModifierToolStack tool, int level) {
      return 0;
    }

    /**
     * Gets the fluid in the given tank
     * @param tool   Tool instance
     * @param level  Modifier level
     * @param tank   Tank index
     * @return  Fluid in the given tank
     */
    default FluidStack getFluidInTank(IModifierToolStack tool, int level, int tank) {
      return FluidStack.EMPTY;
    }

    /**
     * Gets the max capacity for the given tank
     * @param tool   Tool instance
     * @param level  Modifier level
     * @param tank   Tank index
     * @return  Fluid in the given tank
     */
    default int getTankCapacity(IModifierToolStack tool, int level, int tank) {
      return 0;
    }

    /**
     * Checks if the fluid is valid for the given tank
     * @param tool   Tool instance
     * @param level  Modifier level
     * @param tank   Tank index
     * @param fluid  Fluid to insert
     * @return  True if the fluid is valid
     */
    default boolean isFluidValid(IModifierToolStack tool, int level, int tank, FluidStack fluid) {
      return true;
    }

    /**
     * Fills fluid into tanks
     * @param tool     Tool instance
     * @param level    Modifier level
     * @param resource FluidStack representing the Fluid and maximum amount of fluid to be filled. If you want to store this stack, make a copy
     * @param action   If SIMULATE, fill will only be simulated.
     * @return Amount of resource that was (or would have been, if simulated) filled.
     */
    int fill(IModifierToolStack tool, int level, FluidStack resource, FluidAction action);

    /**
     * Drains fluid out of tanks, distribution is left entirely to the IFluidHandler.
     * @param tool     Tool instance
     * @param level    Modifier level
     * @param resource FluidStack representing the Fluid and maximum amount of fluid to be drained.
     * @param action   If SIMULATE, drain will only be simulated.
     * @return FluidStack representing the Fluid and amount that was (or would have been, if
     * simulated) drained.
     */
    FluidStack drain(IModifierToolStack tool, int level, FluidStack resource, FluidAction action);

    /**
     * Drains fluid out of internal tanks, distribution is left entirely to the IFluidHandler.
     * @param tool     Tool instance
     * @param level    Modifier level
     * @param maxDrain Maximum amount of fluid to drain.
     * @param action   If SIMULATE, drain will only be simulated.
     * @return FluidStack representing the Fluid and amount that was (or would have been, if
     * simulated) drained.
     */
    FluidStack drain(IModifierToolStack tool, int level, int maxDrain, FluidAction action);
  }

  /** Helper to run a function from {@link IFluidModifier} */
  @FunctionalInterface
  private interface ITankCallback<T> {
    T run(IFluidModifier module, IModifierToolStack tool, int level, int tank);
  }
}
