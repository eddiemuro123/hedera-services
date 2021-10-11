pragma solidity ^0.5.0;

contract DoSStores {
  uint256[] targets;

  constructor(uint32 n) public {
    targets = new uint256[](n);
  }

  function hitSomeTargets(uint32[] memory at, uint256 v) public {
    for (uint32 i = 0; i < at.length; i++) {
      targets[at[i]] = v;
    } 
  } 
}
