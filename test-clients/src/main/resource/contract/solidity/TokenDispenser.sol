pragma solidity ^0.5.3;

/*
* Dispenses a specified HTS token for tinybars at a 1:1 exchange rate.
*/
contract TokenDispenser {
  /* Address of a precompiled contract that looks up the caller's balance of a a given HTS token */ 
  address constant TOKEN_BALANCE_LIB = address(0x0000000000000000000000000000000000000888);
  /* Precompiled contract that transfers some of the caller's balance of a given HTS token to a specified receiver */
  address constant TOKEN_TRANSFER_LIB = address(0x0000000000000000000000000000000000000777);

  /* The address of the HTS token this contract will dispense */
  address htsTokenType;

  constructor(address _htsTokenType) public payable {
    htsTokenType = _htsTokenType;
  }  
 
  /* 
  * Uses the precompiled contracts to transfer to the calling account as many HTS tokens as it sent in tinybars.
  */
  function dispense() public payable {
    /* Check how many HTS tokens we have left */
    (bool balanceSuccess, bytes memory balanceData) = TOKEN_BALANCE_LIB.delegatecall(abi.encodePacked(htsTokenType));
    require(balanceSuccess);
    uint availTokens = uintFrom(balanceData);

    /* Compare our remaining tokens to the tinybars sent with the message call */
    uint tokensRequested = msg.value;
    require(availTokens >= tokensRequested);

    /* Dispense the purchased tokens */
    bytes memory transferArgs = abi.encodePacked(msg.sender, htsTokenType, msg.value);  
    (bool transferSuccess, bytes memory transferData) = TOKEN_TRANSFER_LIB.delegatecall(transferArgs);
    require(transferSuccess);
  }

  function uintFrom(bytes memory _bytes) internal pure returns (uint) {
    uint ans;

    assembly {
        ans := mload(add(add(_bytes, 0x20), 0))
    }

    return ans;
  }
}
