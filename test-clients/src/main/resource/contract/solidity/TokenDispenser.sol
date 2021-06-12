pragma solidity ^0.5.3;

contract TokenDispenser {
  address tokenTransfer = address(0x0000000000000000000000000000000000000777);

  function() external payable {}
  constructor() public payable {}  

  function dispense() public payable {
    bytes memory amount = abi.encodePacked(msg.value);  
    (bool success, bytes memory data) = tokenTransfer.delegatecall(amount);
    require(success);
  }
}
