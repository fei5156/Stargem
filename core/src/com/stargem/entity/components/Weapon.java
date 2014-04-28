/**
 * 
 */
package com.stargem.entity.components;

/**
 * Weapon.java
 *
 * @author 	Chris B
 * @date	25 Apr 2014
 * @version	1.0
 */
public class Weapon extends AbstractComponent {

	// bit set of all weapons carried by the entity
	public int weapons;
	
	// the currently equipped weapon
	public int currentWeapon;
	
	// whether the weapon is firing now
	public boolean isShooting;
	
	// whether the weapon is ready to fire
	public boolean isReady;
	
	// the maximum and current heat of the weapon
	public int maxHeat;
	public float currentHeat;
	
	// the amount of heat generated by one shot
	public int heatRate;
	
	// the time the weapon takes to lose heat
	// weapons do not cool when firing
	public int coolRate;
	
	// penalty in seconds for over heating
	public int overHeatingPenalty;
	
	// penalty remaining before the weapon is usable again
	public float remainingPenalty;
}